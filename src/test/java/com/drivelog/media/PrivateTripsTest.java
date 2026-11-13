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
    @Test void drivingMarksBecomeSharedProtectedClipsOnlyAfterConsent() throws Exception {
        var store=new PrivateTrips(library);
        UUID owner=store.issue().accountId(), child=store.issue().accountId(), guardian=store.issue().accountId();
        var seats=List.of(new PrivateTrips.Seat(owner,false,null),new PrivateTrips.Seat(child,true,guardian));
        var departure=new PrivateTrips.Departure(UUID.randomUUID(),owner,seats,java.time.Instant.now());
        String id=store.depart(departure).tripId();
        UUID mark=UUID.randomUUID(),later=UUID.randomUUID();
        var body=new PrivateTrips.MarkInput(PrivateTrips.MarkKind.TAP,2000L);
        store.mark(id,owner,later,new PrivateTrips.MarkInput(PrivateTrips.MarkKind.VOICE,4000L));
        assertThat(store.mark(id,child,mark,body)).isEqualTo(store.mark(id,child,mark,body));
        assertThatThrownBy(()->store.mark(id,owner,mark,body)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->store.timeline(id,guardian)).isInstanceOf(ResponseStatusException.class);
        assertThat(store.timeline(id,owner).entries().getFirst().mark().id()).isEqualTo(mark);
        assertThatThrownBy(()->store.input(id,owner)).isInstanceOf(ResponseStatusException.class);
        var parked=new PrivateTrips.Registration(departure.sourceId(),owner,seats,new PrivateTrips.Source("P",true,"front",0,0,true));
        assertThat(store.register(parked).phase()).isEqualTo("PENDING");
        assertThat(store.depart(departure).phase()).isEqualTo("PENDING");
        var yes=new PrivateTrips.Decision(true,true,true,true);
        for(var actor:List.of(owner,child)) store.decide(id,actor,PrivateTrips.Role.PASSENGER,actor,yes);
        store.decide(id,child,PrivateTrips.Role.GUARDIAN,guardian,new PrivateTrips.Decision(true,true,true,false));
        store.attachContext(id,owner,new PrivateTrips.DriveContext(null,null,List.of(new PrivateTrips.DriveEvent(2000,PrivateTrips.EventKind.LANDMARK,"바다"))));
        byte[] bytes=Files.readAllBytes(Path.of("src/test/resources/media/short.mp4"));
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        for(String side:List.of("front","rear")) store.upload(id,owner,side,sha,bytes.length,new java.io.ByteArrayInputStream(bytes));
        Path export=Files.createDirectory(store.work(id).resolve("version"));
        Files.writeString(export.resolve("cards.json"),"""
            {"cards":[{"id":"moment-001","title":"바다","file":"moment-001.mp4","markIds":["%s"]}]}
            """.formatted(mark));
        Files.writeString(export.resolve("master-moment-001.mp4"),"protected");
        Files.writeString(export.resolve("moment-001.mp4"),"preview");
        store.protectDraft(id,export);
        assertThat(store.timeline(id,child).entries().getFirst().state()).isEqualTo("SHARING_DISABLED");
        assertThatThrownBy(()->store.markedClip(id,child,mark)).isInstanceOf(ResponseStatusException.class);
        store.decide(id,child,PrivateTrips.Role.GUARDIAN,guardian,new PrivateTrips.Decision(true,true,null,true));
        assertThat(store.timeline(id,child).entries().getFirst().place()).isEqualTo("바다");
        assertThat(store.markedClip(id,child,mark)).hasContent("preview");
        assertThat(store.timeline(id,child).entries().get(1).state()).isEqualTo("NO_CLIP");
        store.discard(id,owner);
        assertThatThrownBy(()->store.markedClip(id,child,mark)).isInstanceOf(ResponseStatusException.class);
        assertThat(Files.readString(library.resolve("private/trips/"+id+"/trip.json"))).doesNotContain(mark.toString(),"바다");
    }
    @Test void routeNeedsEveryReceiptAndWithdrawalPurgesIt() throws Exception {
        var store=new PrivateTrips(library);
        UUID owner=store.issue().accountId(),child=store.issue().accountId();
        var spec=new PrivateTrips.Registration(UUID.randomUUID(),owner,
            List.of(new PrivateTrips.Seat(owner,false,null),new PrivateTrips.Seat(child,true,owner)),
            new PrivateTrips.Source("P",true,"front",0,0,true));
        String id=store.register(spec).tripId();
        var optIn=new PrivateTrips.Decision(true,true,true);
        store.decide(id,owner,PrivateTrips.Role.PASSENGER,owner,optIn);
        store.decide(id,child,PrivateTrips.Role.PASSENGER,child,optIn);
        store.decide(id,child,PrivateTrips.Role.GUARDIAN,owner,new PrivateTrips.Decision(true,true,false));
        var context=new PrivateTrips.DriveContext("서울","속초",List.of(new PrivateTrips.DriveEvent(1234,PrivateTrips.EventKind.ARRIVAL,"속초")));
        assertThatThrownBy(()->store.attachContext(id,owner,context)).isInstanceOf(ResponseStatusException.class);
        store.decide(id,child,PrivateTrips.Role.GUARDIAN,owner,optIn);
        store.attachContext(id,owner,context);
        var manifest=library.resolve("private/trips/"+id+"/trip.json");
        assertThat(Files.readString(manifest)).contains("속초");
        assertThat(store.decide(id,child,PrivateTrips.Role.GUARDIAN,owner,new PrivateTrips.Decision(true,true,false)).phase()).isEqualTo("DISCARDED");
        assertThat(Files.readString(manifest)).doesNotContain("속초");
    }

    @Test void independentDecisionsThenFinalizeAndResumeInterruptedPurge() throws Exception {
        var store=new PrivateTrips(library);
        UUID owner=store.issue().accountId(), child=store.issue().accountId(), guardian=store.issue().accountId(),source=UUID.randomUUID();
        var sourceInfo=new PrivateTrips.Source("P",true,"front",0,0,true);
        var request=new PrivateTrips.Registration(source,owner,List.of(new PrivateTrips.Seat(owner,false,null),new PrivateTrips.Seat(child,true,guardian)),sourceInfo);
        String id=store.register(request).tripId();
        assertThat(store.register(request).tripId()).isEqualTo(id);
        var changed=new PrivateTrips.Source("P",true,"front",0,1000,true);
        assertThatThrownBy(()->store.register(new PrivateTrips.Registration(source,owner,request.occupants(),changed)))
                .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(409));
        byte[] bytes=Files.readAllBytes(Path.of("src/test/resources/media/short.mp4"));
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
        byte[] invalid = "not a video".getBytes();
        String badSha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(invalid));
        assertThatThrownBy(() -> store.upload(id,owner,"front",badSha,invalid.length,new java.io.ByteArrayInputStream(invalid)))
                .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        // During the first body read the entire request is reserved, even for a second trip.
        var reservationProbe=new java.io.ByteArrayInputStream(bytes) {
            @Override public int read(byte[] dst,int start,int len) {
                synchronized(store) {
                    if (available()==bytes.length) {
                        assertThatThrownBy(() -> store.checkSpace(PrivateTrips.EDIT_HEADROOM+bytes.length,1))
                            .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(507));
                    }
                }
                return super.read(dst,start,len);
            }
        };
        assertThat(store.upload(id,owner,"front",sha,bytes.length,reservationProbe).phase()).isEqualTo("UPLOAD");
        assertThat(store.upload(id,owner,"rear",sha,bytes.length,new java.io.ByteArrayInputStream(bytes)).phase()).isEqualTo("READY");
        byte[] alternate=Arrays.copyOf(bytes,bytes.length+8);
        // Append a legal empty MP4 free box to change only the digest.
        System.arraycopy(new byte[]{0,0,0,8,'f','r','e','e'},0,alternate,bytes.length,8);
        String altSha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(alternate));
        assertThat(store.upload(id,owner,"front",altSha,alternate.length,new java.io.ByteArrayInputStream(alternate)).phase()).isEqualTo("READY");
        assertThatThrownBy(() -> store.upload(id,owner,"front",badSha,invalid.length,new java.io.ByteArrayInputStream(invalid)))
            .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
        Path raw = library.resolve("private/trips/"+id+"/raw");
        Files.writeString(raw.resolve("upload-crash.part"),"partial");
        store.recover();
        assertThat(raw.resolve("upload-crash.part")).doesNotExist();
        assertThat(raw.resolve("front-"+altSha+".mp4")).hasBinaryContent(alternate);
        assertThatThrownBy(() -> store.checkSpace(PrivateTrips.EDIT_HEADROOM+100,101))
            .isInstanceOfSatisfying(ResponseStatusException.class,e -> assertThat(e.getStatusCode().value()).isEqualTo(507));
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
