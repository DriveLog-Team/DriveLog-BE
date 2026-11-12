package com.drivelog.media;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class PrivateTripsTest {
    @TempDir Path library;
    final String manifest="""
        {"front":{"file":"front.mp4","offsetMs":0},"rear":{"file":"rear.mp4","offsetMs":0},
        "audioSource":"front","extraction":{"gear":"P","stationary":true,"occupantCount":2,
        "occupants":[{"ageBand":"14plus","allowed":true},{"ageBand":"under14","guardianAllowed":true}]},
        "audioProcessingConsent":true,"geminiTranscriptConsent":true,"roadReview":{"complete":true}}
        """;
    @Test void independentDecisionsThenFinalizeAndResumeInterruptedPurge() throws Exception {
        var store=new PrivateTrips(library);
        UUID owner=store.issue().accountId(), child=store.issue().accountId(), guardian=store.issue().accountId(),source=UUID.randomUUID();
        var sourceInfo=new PrivateTrips.Source("P",true,"front",0,0,true);
        var request=new PrivateTrips.Registration(source,owner,List.of(new PrivateTrips.Seat(owner,false,null),new PrivateTrips.Seat(child,true,guardian)),sourceInfo);
        String id=store.register(request).tripId();
        byte[] bytes="video".getBytes();
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThatThrownBy(()->store.upload(id,owner,"front",sha,bytes.length,new java.io.ByteArrayInputStream(bytes))).isInstanceOf(ResponseStatusException.class);
        assertThat(library.resolve("private/trips/"+id+"/raw")).doesNotExist();
        var yes=new PrivateTrips.Decision(true,true);
        store.decide(id,owner,PrivateTrips.Role.PASSENGER,owner,yes);
        store.decide(id,child,PrivateTrips.Role.PASSENGER,child,yes);
        assertThatThrownBy(()->store.input(id,owner)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.decide(id,child,PrivateTrips.Role.GUARDIAN,owner,yes)).isInstanceOf(ResponseStatusException.class);
        store.decide(id,child,PrivateTrips.Role.GUARDIAN,guardian,yes);
        assertThatThrownBy(()->store.upload(id,child,"front",sha,bytes.length,new java.io.ByteArrayInputStream(bytes))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.upload(id,owner,"front","0".repeat(64),bytes.length,new java.io.ByteArrayInputStream(bytes))).isInstanceOf(ResponseStatusException.class);
        assertThat(library.resolve("private/trips/"+id+"/raw/front.mp4")).doesNotExist();
        assertThat(store.upload(id,owner,"front",sha,bytes.length,new java.io.ByteArrayInputStream(bytes)).phase()).isEqualTo("UPLOAD");
        assertThat(store.upload(id,owner,"rear",sha,bytes.length,new java.io.ByteArrayInputStream(bytes)).phase()).isEqualTo("READY");
        var input=store.input(id,owner);
        var export=Files.createDirectory(store.work(id).resolve("export"));
        var video=Files.writeString(export.resolve("moment-001.mp4"),"edited content");
        Files.writeString(export.resolve("cards.json"),"{\"cards\":[{\"id\":\"moment-001\",\"file\":\"moment-001.mp4\",\"title\":\"Memory\",\"startMs\":1000,\"endMs\":6500}]}");
        Files.writeString(export.resolve("master-moment-001.mp4"),"protected edit source");
        store.protectDraft(id,export);
        assertThat(input.resolveSibling("raw")).doesNotExist();
        assertThat(Files.readString(input)).contains("draftVersion");
        UUID jobId=UUID.randomUUID();
        var job=new MediaJobController.Job(jobId,id,"STORY_15","READY",input,export,null);
        var media=mock(MediaJobController.class);var req=new MockHttpServletRequest();
        when(media.owned(jobId,req)).thenReturn(job);when(media.clip(job,"moment-001")).thenReturn(video);
        var selection=new PrivateTrips.Selection(jobId,"moment-001");
        assertThat(store.finish(id,owner,selection,media,req).phase()).isEqualTo("SAVED");
        assertThat(store.finish(id,owner,selection,media,req).phase()).isEqualTo("SAVED");
        assertThat(input.resolveSibling("raw")).doesNotExist();
        assertThat(input.resolveSibling("work")).doesNotExist();
        assertThat(store.items(child)).isEmpty();
        assertThatThrownBy(()->store.saved(id,child)).isInstanceOf(ResponseStatusException.class);
        // Crash after the selected clip is durable and before the second deletion finishes.
        Files.createDirectories(input.resolveSibling("work"));
        Files.writeString(input,Files.readString(input).replace("\"SAVED\"","\"SAVING\""));
        var restarted=new PrivateTrips(library);restarted.recover();
        assertThat(input.resolveSibling("work")).doesNotExist();
        assertThat(Files.readString(restarted.saved(id,owner))).isEqualTo("edited content");
        restarted.decide(id,child,PrivateTrips.Role.GUARDIAN,guardian,new PrivateTrips.Decision(false,false));
        assertThat(restarted.items(owner)).isEmpty();
        assertThat(input.resolveSibling("saved.mp4")).doesNotExist();
    }
}
