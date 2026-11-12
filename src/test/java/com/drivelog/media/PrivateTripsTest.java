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
        var inbox=Files.createDirectories(library.resolve("private/inbox/"+source));
        Files.writeString(inbox.resolve("trip.json"),manifest);
        Files.writeString(inbox.resolve("front.mp4"),"front");
        Files.writeString(inbox.resolve("rear.mp4"),"rear");
        var request=new PrivateTrips.Registration(source,owner,List.of(new PrivateTrips.Seat(owner,false,null),new PrivateTrips.Seat(child,true,guardian)));
        String id=store.register(request).tripId();
        assertThat(inbox).doesNotExist();
        var yes=new PrivateTrips.Decision(true,true);
        store.decide(id,owner,PrivateTrips.Role.PASSENGER,owner,yes);
        store.decide(id,child,PrivateTrips.Role.PASSENGER,child,yes);
        assertThatThrownBy(()->store.input(id,owner)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.decide(id,child,PrivateTrips.Role.GUARDIAN,owner,yes)).isInstanceOf(ResponseStatusException.class);
        store.decide(id,child,PrivateTrips.Role.GUARDIAN,guardian,yes);
        var input=store.input(id,owner);
        var export=Files.createDirectory(store.work(id).resolve("export"));
        var video=Files.writeString(export.resolve("moment-001.mp4"),"edited content");
        Files.writeString(export.resolve("cards.json"),"{\"cards\":[{\"id\":\"moment-001\",\"title\":\"Memory\",\"startMs\":1000,\"endMs\":6500}]}");
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
