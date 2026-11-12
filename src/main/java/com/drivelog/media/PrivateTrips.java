package com.drivelog.media;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.springframework.http.HttpStatus.*;
import static java.nio.file.StandardCopyOption.*;

/** Single VM store. One atomic manifest governs both the HTTP API and the media worker. */
@org.springframework.scheduling.annotation.EnableScheduling
@Service
@Profile("media")
class PrivateTrips {
    record Account(UUID accountId, String accessToken) {}
    record Seat(UUID occupant, boolean under14, UUID guardian) {}
    record Registration(UUID sourceId, UUID owner, List<Seat> occupants, Source source) {}
    record Source(String gear, boolean stationary, String audioSource, long frontOffsetMs, long rearOffsetMs, boolean roadReviewed) {}
    enum Role { PASSENGER, GUARDIAN }
    record Decision(Boolean extraction, Boolean geminiTranscript) {}
    record Receipt(Role role, UUID occupant, UUID actor, boolean extraction, boolean geminiTranscript, String at) {}
    record Trip(String tripId, UUID owner, String phase, List<Seat> occupants, List<Receipt> receipts) {}
    record Selection(UUID jobId, String moment) {}
    record Item(String tripId, String title, long durationMs, String savedAt) {}

    private final JsonMapper json=JsonMapper.builder().build();
    private final SecureRandom random=new SecureRandom();
    private final Path accounts, inbox, trips;
    private final Map<String,Integer> workers=new HashMap<>();

    PrivateTrips(@Value("${media.library}") Path library) throws IOException {
        Path root=Files.createDirectories(library.resolve("private")).toRealPath();
        accounts=Files.createDirectories(root.resolve("accounts"));
        inbox=Files.createDirectories(root.resolve("inbox"));
        trips=Files.createDirectories(root.resolve("trips"));
    }
    @PostConstruct synchronized void recover() throws IOException {
        try(var entries=Files.list(trips)) {
            for(Path entry:entries.toList()) {
                if (!Files.isRegularFile(entry.resolve("trip.json"))) continue;
                ObjectNode doc=read(entry.getFileName().toString());
                if ("IMPORTING".equals(phase(doc))) finishImport(doc);
                if(!access(doc).has("draftVersion") && Set.of("READY","UPLOAD","PENDING").contains(phase(doc))) deleteTree(entry.resolve("work"));
                if(access(doc).has("draftVersion")) deleteTree(entry.resolve("raw"));
                cleanup(doc);
            }
        }
    }
    static boolean managed(String id) { return id!=null && id.startsWith("trip-"); }
    private Path dir(String id) {
        if(id==null || !id.matches("trip-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw new ResponseStatusException(NOT_FOUND);
        return trips.resolve(id);
    }
    private ObjectNode read(String id) throws IOException {
        Path manifest=dir(id).resolve("trip.json");
        if(!Files.isRegularFile(manifest)) throw new ResponseStatusException(NOT_FOUND);
        return (ObjectNode)json.readTree(manifest.toFile());
    }
    private static ObjectNode access(ObjectNode doc) { return (ObjectNode)doc.get("access"); }
    private static String phase(ObjectNode doc) { return access(doc).path("phase").asText(); }
    private void store(ObjectNode doc) throws IOException {
        Path parent=dir(doc.get("tripId").asText());
        Path draft=Files.createTempFile(parent,".manifest-",".tmp");
        try {
            json.writeValue(draft.toFile(),doc);
            Files.move(draft,parent.resolve("trip.json"),ATOMIC_MOVE,REPLACE_EXISTING);
        } finally { Files.deleteIfExists(draft); }
    }
    synchronized Account issue() throws IOException {
        byte[] secret=new byte[32];random.nextBytes(secret);
        UUID id=UUID.randomUUID();
        String token=id+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Files.write(accounts.resolve(id.toString()),hash(token),StandardOpenOption.CREATE_NEW);
        return new Account(id,token);
    }
    private byte[] hash(String token) {
        try {return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    UUID person(HttpServletRequest request) throws IOException {
        String h=request.getHeader("Authorization");
        if(!request.isSecure() || h==null || !h.matches("Bearer [0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")) throw new ResponseStatusException(UNAUTHORIZED);
        String token=h.substring(7);
        UUID id;
        try {id=UUID.fromString(token.substring(0,36));}catch(IllegalArgumentException e){throw new ResponseStatusException(UNAUTHORIZED);}
        Path record=accounts.resolve(id.toString());
        if(!Files.isRegularFile(record) || !MessageDigest.isEqual(hash(token),Files.readAllBytes(record))) throw new ResponseStatusException(UNAUTHORIZED);
        return id;
    }
    private void known(UUID id) {
        if(id==null || !Files.isRegularFile(accounts.resolve(id.toString()))) throw new ResponseStatusException(BAD_REQUEST);
    }
    synchronized Trip register(Registration reg) throws IOException {
        if(reg==null || reg.sourceId()==null || reg.occupants()==null || reg.occupants().isEmpty() || reg.occupants().size()>16) throw new ResponseStatusException(BAD_REQUEST);
        known(reg.owner());
        Set<UUID> people=new HashSet<>();
        for(Seat s:reg.occupants()) {
            if(s==null) throw new ResponseStatusException(BAD_REQUEST);
            known(s.occupant());
            if(!people.add(s.occupant()) || s.under14()!=(s.guardian()!=null) || s.occupant().equals(s.guardian())) throw new ResponseStatusException(BAD_REQUEST);
            if(s.under14()) known(s.guardian());
        }
        if(!people.contains(reg.owner()) || reg.occupants().stream().anyMatch(s -> s.under14() && reg.occupants().stream().anyMatch(child -> s.occupant().equals(child.guardian())))) throw new ResponseStatusException(BAD_REQUEST);
        String id="trip-"+reg.sourceId();
        Path folder=dir(id);
        if(Files.isRegularFile(folder.resolve("trip.json"))) {
            ObjectNode prior=read(id);
            if(!reg.owner().toString().equals(access(prior).path("owner").asText()) || !access(prior).get("seats").equals(json.valueToTree(reg.occupants()))) throw new ResponseStatusException(CONFLICT);
            if(access(prior).has("source") && !access(prior).get("source").equals(json.valueToTree(reg.source()))) throw new ResponseStatusException(CONFLICT);
            if("IMPORTING".equals(phase(prior))) finishImport(prior);
            return view(prior);
        }
        Source source=reg.source();
        if(source==null || !"P".equals(source.gear()) || !source.stationary() || !source.roadReviewed()
                || !List.of("front","rear").contains(Objects.toString(source.audioSource(),""))
                || source.frontOffsetMs()<0 || source.rearOffsetMs()<0) throw new ResponseStatusException(BAD_REQUEST);
        ObjectNode doc=json.createObjectNode(); doc.put("tripId",id);
        doc.putObject("front").put("file","raw/front.mp4").put("offsetMs",source.frontOffsetMs());
        doc.putObject("rear").put("file","raw/rear.mp4").put("offsetMs",source.rearOffsetMs());
        doc.put("audioSource",source.audioSource()).put("recommendationMode","gleo-demo").put("gleoExcerptConsent",false);
        doc.putObject("roadReview").put("complete",true);
        doc.putObject("extraction").put("gear","P").put("stationary",true).put("occupantCount",people.size());
        ObjectNode a=doc.putObject("access");a.put("owner",reg.owner().toString()).put("phase","PENDING");
        a.put("expiresAt",Instant.now().plusSeconds(86400).toString());
        a.set("source",json.valueToTree(source));
        a.putObject("uploads");
        a.set("seats",json.valueToTree(reg.occupants()));a.putObject("receipts");
        refresh(doc);
        Files.createDirectories(folder);store(doc);
        return view(doc);
    }
    private void finishImport(ObjectNode doc) throws IOException {
        String id=doc.get("tripId").asText();Path raw=dir(id).resolve("raw");
        if(!Files.exists(raw)) Files.move(inbox.resolve(id.substring(5)),raw,ATOMIC_MOVE);
        access(doc).put("phase","PENDING");store(doc);
    }
    private ObjectNode uploadAllowed(String id, UUID actor, String side, String digest) throws IOException {
        owner(id,actor);
        if(!Set.of("front","rear").contains(side) || digest==null || !digest.matches("[a-f0-9]{64}"))
            throw new ResponseStatusException(BAD_REQUEST);
        ObjectNode doc=read(id);
        if(!access(doc).has("uploads") || !doc.path("audioProcessingConsent").asBoolean()
                || !Set.of("UPLOAD","READY").contains(phase(doc))) throw new ResponseStatusException(FORBIDDEN);
        return doc;
    }
    Trip upload(String id, UUID actor, String side, String digest, long length, InputStream bytes) throws IOException {
        long limit=8L*1024*1024*1024;
        Path raw=dir(id).resolve("raw"), temp;
        synchronized(this) {
            ObjectNode doc=uploadAllowed(id,actor,side,digest);
            String prior=access(doc).get("uploads").path(side).asText("");
            if(!prior.isEmpty()) {
                if(!prior.equals(digest)) throw new ResponseStatusException(CONFLICT);
                return view(doc);
            }
            if(length<=0 || length>limit) throw new ResponseStatusException(BAD_REQUEST);
            Files.createDirectories(raw);
            if(Files.getFileStore(raw).getUsableSpace()-length<2_000_000_000L) throw new ResponseStatusException(INSUFFICIENT_STORAGE);
            temp=Files.createTempFile(raw,"upload-",".part");
            started(id);
        }
        try {
            MessageDigest sha;
            try {sha=MessageDigest.getInstance("SHA-256");} catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            long total=0;
            try(var output=Files.newOutputStream(temp,StandardOpenOption.WRITE)) {
                byte[] buffer=new byte[256*1024];
                for(int n;(n=bytes.read(buffer))!=-1;) {
                    total+=n;
                    if(total>length) throw new ResponseStatusException(BAD_REQUEST);
                    synchronized(this){uploadAllowed(id,actor,side,digest);}
                    sha.update(buffer,0,n);output.write(buffer,0,n);
                }
            }
            if(total!=length || !HexFormat.of().formatHex(sha.digest()).equals(digest)) throw new ResponseStatusException(BAD_REQUEST);
            synchronized(this) {
                ObjectNode doc=uploadAllowed(id,actor,side,digest);
                var uploads=(ObjectNode)access(doc).get("uploads");
                if(uploads.has(side) && !digest.equals(uploads.path(side).asText())) throw new ResponseStatusException(CONFLICT);
                Files.move(temp,raw.resolve(side+".mp4"),ATOMIC_MOVE,REPLACE_EXISTING);
                uploads.put(side,digest);
                if(uploads.has("front") && uploads.has("rear")) access(doc).put("phase","READY");
                store(doc);return view(doc);
            }
        } finally {
            try {Files.deleteIfExists(temp);} finally {stopped(id);}
        }
    }
    private List<Seat> seats(ObjectNode doc) {
        return Arrays.asList(json.treeToValue(access(doc).get("seats"),Seat[].class));
    }
    private Trip view(ObjectNode doc) {
        List<Receipt> receipts=new ArrayList<>();
        access(doc).get("receipts").forEach(n->receipts.add(json.treeToValue(n,Receipt.class)));
        return new Trip(doc.get("tripId").asText(),UUID.fromString(access(doc).get("owner").asText()),phase(doc),seats(doc),receipts);
    }
    synchronized Trip status(String id,UUID actor) throws IOException {
        ObjectNode doc=read(id);
        if(seats(doc).stream().noneMatch(s->actor.equals(s.occupant()) || actor.equals(s.guardian()))) throw new ResponseStatusException(NOT_FOUND);
        cleanup(doc);return view(doc);
    }
    synchronized List<Trip> assigned(UUID actor) throws IOException {
        List<Trip> result=new ArrayList<>();
        try(var entries=Files.list(trips)) {
            for(Path entry:entries.toList()) {
                if(!Files.isRegularFile(entry.resolve("trip.json"))) continue;
                ObjectNode doc=read(entry.getFileName().toString());
                if(seats(doc).stream().anyMatch(s->actor.equals(s.occupant()) || actor.equals(s.guardian()))) result.add(view(doc));
            }
        }
        return result;
    }
    private boolean refresh(ObjectNode doc) {
        var people=((ObjectNode)doc.get("extraction")).putArray("occupants");
        var receipts=access(doc).get("receipts");boolean all=true;
        for(Seat s:seats(doc)) {
            boolean self=agreed(receipts.path(s.occupant()+"/PASSENGER"));
            boolean guardian=!s.under14() || agreed(receipts.path(s.occupant()+"/GUARDIAN"));
            people.addObject().put("ageBand",s.under14()?"under14":"14plus").put("allowed",self).put("guardianAllowed",guardian);
            all &= self && guardian;
        }
        doc.put("audioProcessingConsent",all).put("geminiTranscriptConsent",all);return all;
    }
    private boolean agreed(JsonNode r){return r.path("extraction").asBoolean(false) && r.path("geminiTranscript").asBoolean(false);}
    synchronized Trip decide(String id, UUID occupant, Role role, UUID actor, Decision d) throws IOException {
        ObjectNode doc=read(id); Seat s=seats(doc).stream().filter(x->x.occupant().equals(occupant)).findFirst().orElseThrow(()->new ResponseStatusException(NOT_FOUND));
        if(!actor.equals(role==Role.PASSENGER?s.occupant():s.guardian())) throw new ResponseStatusException(FORBIDDEN);
        if(d==null || d.extraction()==null || d.geminiTranscript()==null) throw new ResponseStatusException(BAD_REQUEST);
        boolean yes=d.extraction() && d.geminiTranscript();
        if("DISCARDED".equals(phase(doc)) && !yes) return view(doc);
        if(!Set.of("PENDING","UPLOAD","READY","SAVED").contains(phase(doc))) throw new ResponseStatusException(CONFLICT);
        var receipts=(ObjectNode)access(doc).get("receipts");String key=occupant+"/"+role;
        if(yes && agreed(receipts.path(key))) return view(doc);
        receipts.set(key,json.valueToTree(new Receipt(role,occupant,actor,d.extraction(),d.geminiTranscript(),Instant.now().toString())));
        boolean all=refresh(doc);
        access(doc).put("phase",yes?(all?(access(doc).has("uploads")?"UPLOAD":"READY"):"PENDING"):"DISCARDING");
        store(doc);cleanup(doc);return view(doc);
    }
    synchronized void owner(String id, UUID person) throws IOException {
        if(!access(read(id)).path("owner").asText().equals(person.toString())) throw new ResponseStatusException(NOT_FOUND);
    }
    synchronized Path input(String id,UUID actor) throws IOException {
        owner(id,actor);
        String phase=phase(read(id));
        if("UPLOAD".equals(phase)) throw new ResponseStatusException(CONFLICT);
        if("PENDING".equals(phase)) throw new ResponseStatusException(FORBIDDEN);
        if(!"READY".equals(phase)) throw new ResponseStatusException(GONE);
        return dir(id).resolve("trip.json");
    }
    synchronized Path work(String id) throws IOException {return Files.createDirectories(dir(id).resolve("work"));}
    synchronized void protectDraft(String id,Path export) throws IOException {
        ObjectNode doc=read(id);
        if(!"READY".equals(phase(doc))) throw new ResponseStatusException(CONFLICT);
        if(!access(doc).has("draftVersion")) {
            JsonNode result=json.readTree(export.resolve("cards.json").toFile());
            for(JsonNode card:result.path("cards")) {
                if(!Files.isRegularFile(export.resolve("master-"+card.path("file").asText()))) throw new IOException("Protected clip missing");
            }
            access(doc).put("draftVersion",export.getFileName().toString());
            access(doc).put("expiresAt",Instant.now().plusSeconds(86400).toString());
            store(doc);
        }
        deleteTree(dir(id).resolve("raw"));
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000)
    synchronized void expireAbandoned() throws IOException {
        try(var entries=Files.list(trips)) {
            for(Path entry:entries.toList()) {
                if(!Files.isRegularFile(entry.resolve("trip.json"))) continue;
                ObjectNode doc=read(entry.getFileName().toString());
                if(Set.of("PENDING","UPLOAD","READY").contains(phase(doc)) && access(doc).has("expiresAt")
                        && !Instant.parse(access(doc).get("expiresAt").asText()).isAfter(Instant.now())) {
                    access(doc).put("phase","DISCARDING");
                    doc.put("audioProcessingConsent",false).put("geminiTranscriptConsent",false);
                    store(doc);
                }
                cleanup(doc);
            }
        }
    }
    synchronized void started(String id){workers.merge(id,1,Integer::sum);}
    synchronized void stopped(String id) throws IOException {
        workers.computeIfPresent(id,(k,n)->n==1?null:n-1);cleanup(read(id));
    }
    synchronized Trip finish(String id,UUID actor,Selection selection, MediaJobController media,HttpServletRequest req) throws IOException {
        owner(id,actor);ObjectNode doc=read(id); ObjectNode a=access(doc);
        if(selection==null || selection.jobId()==null || selection.moment()==null) throw new ResponseStatusException(BAD_REQUEST);
        if(Set.of("SAVING","SAVED").contains(phase(doc))) {
            if(!json.valueToTree(selection).equals(a.get("selection"))) throw new ResponseStatusException(CONFLICT);
            cleanup(doc);return view(doc);
        }
        if(!"READY".equals(phase(doc)) || workers.containsKey(id)) throw new ResponseStatusException(CONFLICT);
        var job=media.owned(selection.jobId(),req);
        if(!id.equals(job.tripId()) || !"READY".equals(job.state())) throw new ResponseStatusException(CONFLICT);
        Path source=media.clip(job,selection.moment());ObjectNode card=null;
        for(JsonNode c:json.readTree(job.export().resolve("cards.json").toFile()).get("cards"))
            if(c.path("id").asText().equals(selection.moment())) card=(ObjectNode)c;
        if(card==null) throw new ResponseStatusException(NOT_FOUND);
        // Hard link on the same volume commits the immutable completed MP4 without copying bytes.
        Path saved=dir(id).resolve("saved.mp4");Files.deleteIfExists(saved);Files.createLink(saved,source);
        a.set("item",json.valueToTree(new Item(id,card.get("title").asText(),card.get("endMs").asLong()-card.get("startMs").asLong(),Instant.now().toString())));
        a.set("selection",json.valueToTree(selection));a.put("phase","SAVING");
        doc.put("audioProcessingConsent",false).put("geminiTranscriptConsent",false);
        store(doc);cleanup(doc);return view(doc);
    }
    synchronized Trip discard(String id,UUID actor) throws IOException {
        owner(id,actor);ObjectNode doc=read(id);
        if(!"DISCARDED".equals(phase(doc))) {
            access(doc).put("phase","DISCARDING");doc.put("audioProcessingConsent",false).put("geminiTranscriptConsent",false);
            store(doc);cleanup(doc);
        }
        return view(doc);
    }
    private void cleanup(ObjectNode doc) throws IOException {
        String id=doc.get("tripId").asText(),phase=phase(doc);
        if(workers.containsKey(id) || !Set.of("SAVING","DISCARDING").contains(phase)) return;
        Path folder=dir(id);
        deleteTree(folder.resolve("raw"));deleteTree(folder.resolve("work"));
        if("DISCARDING".equals(phase)) {Files.deleteIfExists(folder.resolve("saved.mp4"));access(doc).remove("item");}
        access(doc).put("phase","SAVING".equals(phase)?"SAVED":"DISCARDED");
        store(doc);
    }
    private void deleteTree(Path root) throws IOException {
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)) return;
        try(var paths=Files.walk(root)) {
            for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
    synchronized List<Item> items(UUID actor) throws IOException {
        List<Item> items=new ArrayList<>();
        try(var entries=Files.list(trips)) {
            for(Path p:entries.toList()) {
                if(!Files.isRegularFile(p.resolve("trip.json"))) continue;
                ObjectNode doc=read(p.getFileName().toString());
                if("SAVED".equals(phase(doc)) && actor.toString().equals(access(doc).path("owner").asText()))
                    items.add(json.treeToValue(access(doc).get("item"),Item.class));
            }
        }
        items.sort(Comparator.comparing(Item::savedAt).reversed());return items;
    }
    synchronized Path saved(String id,UUID actor) throws IOException {
        owner(id,actor);if(!"SAVED".equals(phase(read(id)))) throw new ResponseStatusException(NOT_FOUND);
        return dir(id).resolve("saved.mp4");
    }
}
