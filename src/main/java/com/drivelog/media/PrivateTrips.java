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
    record Departure(UUID sourceId, UUID owner, List<Seat> occupants, Instant startedAt) {}
    enum MarkKind { TAP, VOICE, VOICE_UNTIL_LAUGHTER }
    record MarkInput(MarkKind kind, Long atMs) {}
    record DriveMark(UUID id, UUID author, MarkKind kind, long atMs) {}
    record TimelineEntry(DriveMark mark, String authorName, String place, String state, String title, String clipUrl) {}
    record Timeline(String tripId, Instant startedAt, String phase, List<TimelineEntry> entries) {}
    record Source(String gear, boolean stationary, String audioSource, long frontOffsetMs, long rearOffsetMs, boolean roadReviewed) {}
    enum Role { PASSENGER, GUARDIAN }
    record Decision(Boolean extraction, Boolean geminiTranscript, Boolean useTripContext, Boolean shareMarkedClips) {
        Decision(Boolean extraction,Boolean geminiTranscript,Boolean useTripContext){this(extraction,geminiTranscript,useTripContext,null);}
        Decision(Boolean extraction, Boolean geminiTranscript){this(extraction,geminiTranscript,false);}
    }
    enum EventKind { LANDMARK, NEAR_ARRIVAL, ARRIVAL }
    record DriveEvent(long atMs, EventKind kind, String label) {}
    record DriveContext(String departure,String destination,List<DriveEvent> events) {}
    record Receipt(Role role, UUID occupant, UUID actor, boolean extraction, boolean geminiTranscript, boolean useTripContext, String at, boolean shareMarkedClips) {}
    record Trip(String tripId, UUID owner, String phase, List<Seat> occupants, List<Receipt> receipts) {}
    record Selection(UUID jobId, String moment) {}
    record Item(String tripId, String title, long durationMs, String savedAt) {}

    private final JsonMapper json=JsonMapper.builder().build();
    @Value("${media.vault-bucket:}") private String vaultBucket="";
    @Value("${media.worker:media/worker.py}") private Path workerScript;
    @Value("${media.python:python3}") private String vaultPython;
    private void vault(String command,ObjectNode doc) throws IOException {
        if (vaultBucket.isBlank()) return;
        Process proc=new ProcessBuilder(vaultPython,workerScript.resolveSibling("vault.py").toString(),command,
                vaultBucket,accounts.getParent().toString(),doc.get("tripId").asText())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try {
            try (var body=proc.getOutputStream()) { json.writeValue(body,doc); }
            if (!proc.waitFor(100,java.util.concurrent.TimeUnit.SECONDS) || proc.exitValue()!=0)
                throw new ResponseStatusException(SERVICE_UNAVAILABLE,"Vault temporarily unavailable");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Vault operation interrupted",e);
        } finally { if (proc.isAlive()) proc.destroyForcibly(); }
    }
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
                Path raw=entry.resolve("raw");
                if (Files.isDirectory(raw) && access(doc).has("uploads")) {
                    try (var files=Files.list(raw)) {
                        for (Path file:files.toList()) {
                            String name=file.getFileName().toString();
                            boolean orphan=name.matches("(front|rear)(-[a-f0-9]{64})?[.]mp4")
                                    && java.util.stream.Stream.of("front","rear").noneMatch(side ->
                                        access(doc).path("uploads").has(side) && ("raw/"+name).equals(doc.path(side).path("file").asText()));
                            if (orphan || (name.startsWith("upload-") && name.endsWith(".part"))) Files.delete(file);
                        }
                    }
                }
                if ("IMPORTING".equals(phase(doc))) finishImport(doc);
                if (Files.isDirectory(entry.resolve("work"))) {
                    try (var paths=Files.list(entry.resolve("work"))) {
                        for (Path part:paths.toList()) {
                            String name=part.getFileName().toString();
                            if (name.startsWith("tmp") || name.startsWith("edit-")) deleteTree(part);
                        }
                    }
                }
                if(access(doc).has("draftVersion")) deleteTree(entry.resolve("raw"));
                try { cleanup(doc); }
                catch (ResponseStatusException e) {
                    if (e.getStatusCode()!=SERVICE_UNAVAILABLE) throw e;
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Vault recovery deferred");
                }
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
    record Invite(String url,Instant expiresAt) {}
    record InviteSeat(boolean under14,UUID guardian) {}
    record JoinInput(String code,String claimKey,String displayName) {}
    record Guest(String tripId,UUID accountId,String accessToken) {}

    @Value("${media.handoff-origin:}") private String publicOrigin="";
    synchronized Invite invite(String id,UUID actor,InviteSeat seat) throws IOException {
        owner(id,actor);
        ObjectNode doc=read(id);
        if(!"DRIVING".equals(phase(doc))) throw new ResponseStatusException(CONFLICT);
        if(seat==null || seat.under14()!=(seat.guardian()!=null)) throw new ResponseStatusException(BAD_REQUEST);
        if(seat.under14() && seats(doc).stream().noneMatch(s -> s.occupant().equals(seat.guardian()) && !s.under14()))
            throw new ResponseStatusException(BAD_REQUEST);
        ObjectNode invites=access(doc).withObjectProperty("invites");
        long now=Instant.now().getEpochSecond();
        for(String key:List.copyOf(invites.propertyNames())) if(invites.get(key).path("until").asLong()<=now) invites.remove(key);
        if(seats(doc).size()>=16 || invites.size()>=32) throw new ResponseStatusException(CONFLICT);
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String code=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant until=Instant.ofEpochSecond(now+1800);
        var invitation=invites.putObject(HexFormat.of().formatHex(hash(code))).put("until",until.getEpochSecond());
        invitation.set("seat",json.valueToTree(seat));
        store(doc);
        return new Invite(publicOrigin.replaceFirst("/$","")+"/api/join.html#"+id+"."+code,until);
    }
    synchronized Guest join(String id,JoinInput input) throws IOException {
        if(input==null || input.code()==null || !input.code().matches("[A-Za-z0-9_-]{43}") ||
           input.claimKey()==null || !input.claimKey().matches("[A-Za-z0-9_-]{43}") || !validName(input.displayName()))
            throw new ResponseStatusException(BAD_REQUEST);
        ObjectNode doc=read(id);
        var key=HexFormat.of().formatHex(hash(input.code()));
        var node=access(doc).path("invites").path(key);
        if(!"DRIVING".equals(phase(doc)) || node.path("until").asLong()<=Instant.now().getEpochSecond()) throw new ResponseStatusException(GONE);
        String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(hash(input.code()+"."+input.claimKey()));
        if(node.has("guest")) {
            UUID existing=UUID.fromString(node.get("guest").asText());
            String token=existing+"."+secret;
            if(!MessageDigest.isEqual(hash(token),Files.readAllBytes(accounts.resolve(existing.toString())))) throw new ResponseStatusException(GONE);
            return new Guest(id,existing,token);
        }
        if(seats(doc).size()>=16) throw new ResponseStatusException(CONFLICT);
        UUID guest=UUID.randomUUID(); String token=guest+"."+secret;
        Files.write(accounts.resolve(guest.toString()),hash(token),StandardOpenOption.CREATE_NEW);
        var a=access(doc);
        if(!a.has("departureSeats")) a.set("departureSeats",a.get("seats").deepCopy());
        InviteSeat seat=json.treeToValue(node.get("seat"),InviteSeat.class);
        a.withArray("seats").add(json.valueToTree(new Seat(guest,seat.under14(),seat.guardian())));
        ((ObjectNode)node).put("guest",guest.toString());
        a.withObjectProperty("names").put(guest.toString(),input.displayName().strip());
        ((ObjectNode)doc.get("extraction")).put("occupantCount",seats(doc).size());
        refresh(doc);store(doc);
        return new Guest(id,guest,token);
    }
    private static boolean validName(String name) {
        return name!=null && !name.isBlank() && name.length()<=32 && name.codePoints().noneMatch(Character::isISOControl);
    }
    synchronized Trip register(Registration reg) throws IOException {
        return register(reg,null);
    }
    synchronized Trip depart(Departure departure) throws IOException {
        if(departure==null || departure.startedAt()==null) throw new ResponseStatusException(BAD_REQUEST);
        return register(new Registration(departure.sourceId(),departure.owner(),departure.occupants(),null),departure.startedAt());
    }
    private Trip register(Registration reg, Instant startedAt) throws IOException {
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
        Source source=reg.source();
        if(startedAt==null && (source==null || !"P".equals(source.gear()) || !source.stationary() || !source.roadReviewed()
                || !List.of("front","rear").contains(Objects.toString(source.audioSource(),""))
                || source.frontOffsetMs()<0 || source.rearOffsetMs()<0)) throw new ResponseStatusException(BAD_REQUEST);
        Path folder=dir(id);
        if(Files.isRegularFile(folder.resolve("trip.json"))) {
            ObjectNode prior=read(id);
            if(!reg.owner().toString().equals(access(prior).path("owner").asText()) || !(access(prior).get("seats").equals(json.valueToTree(reg.occupants())) || access(prior).path("departureSeats").equals(json.valueToTree(reg.occupants())))) throw new ResponseStatusException(CONFLICT);
            if(startedAt!=null) {
                if(!startedAt.toString().equals(access(prior).path("startedAt").asText())) throw new ResponseStatusException(CONFLICT);
                return view(prior);
            }
            if("DRIVING".equals(phase(prior))) {
                access(prior).remove("invites");
                configureParked(prior,source);
                access(prior).put("phase","PENDING");
                store(prior);
                return view(prior);
            }
            if(access(prior).has("source")) {
                Source registered=json.treeToValue(access(prior).get("source"),Source.class);
                if(!registered.equals(reg.source())) throw new ResponseStatusException(CONFLICT);
            }
            if("IMPORTING".equals(phase(prior))) finishImport(prior);
            return view(prior);
        }
        ObjectNode doc=json.createObjectNode(); doc.put("tripId",id);
        doc.putObject("extraction").put("occupantCount",people.size());
        ObjectNode a=doc.putObject("access");a.put("owner",reg.owner().toString()).put("phase","PENDING");
        if(startedAt!=null) a.put("phase","DRIVING").put("startedAt",startedAt.toString());
        else configureParked(doc,source);
        a.put("expiresAt",Instant.now().plusSeconds(86400).toString());
        a.putObject("uploads");
        a.set("seats",json.valueToTree(reg.occupants()));a.putObject("receipts");
        refresh(doc);
        Files.createDirectories(folder);store(doc);
        return view(doc);
    }
    private void configureParked(ObjectNode doc,Source source) {
        doc.putObject("front").put("file","raw/front.mp4").put("offsetMs",source.frontOffsetMs());
        doc.putObject("rear").put("file","raw/rear.mp4").put("offsetMs",source.rearOffsetMs());
        doc.put("audioSource",source.audioSource()).put("recommendationMode","gleo-demo").put("gleoExcerptConsent",false);
        doc.putObject("roadReview").put("complete",true);
        ((ObjectNode)doc.get("extraction")).put("gear","P").put("stationary",true);
        access(doc).set("source",json.valueToTree(source));
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
    private long reservedUploadBytes;
    static final long EDIT_HEADROOM = 2_000_000_000L;

    synchronized void checkSpace(long available, long requested) {
        if (available - EDIT_HEADROOM - reservedUploadBytes < requested)
            throw new ResponseStatusException(INSUFFICIENT_STORAGE);
    }
    private final Set<String> editing = new HashSet<>();
    private final Set<String> uploading = new HashSet<>();

    Trip upload(String id, UUID actor, String side, String digest, long length, InputStream bytes) throws IOException {
        long limit=8L*1024*1024*1024;
        Path raw=dir(id).resolve("raw"), temp;
        String key=id+"/"+side;
        boolean requireAudio;
        long remaining=length;
        synchronized(this) {
            ObjectNode doc=uploadAllowed(id,actor,side,digest);
            String prior=access(doc).get("uploads").path(side).asText("");
            if (access(doc).has("draftVersion")) throw new ResponseStatusException(CONFLICT);
            if (uploading.contains(key) || editing.contains(id)) throw new ResponseStatusException(CONFLICT);
            if (prior.equals(digest) && access(doc).path("validated").path(side).asBoolean()) return view(doc);
            requireAudio=side.equals(doc.path("audioSource").asText());
            if(length<=0 || length>limit) throw new ResponseStatusException(BAD_REQUEST);
            Files.createDirectories(raw);
            checkSpace(Files.getFileStore(raw).getUsableSpace(),length);
            temp=Files.createTempFile(raw,"upload-",".part");
            reservedUploadBytes+=length;
            uploading.add(key);
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
                    synchronized(this) { reservedUploadBytes-=n; remaining-=n; }
                }
            }
            if(total!=length || !HexFormat.of().formatHex(sha.digest()).equals(digest)) throw new ResponseStatusException(BAD_REQUEST);
            VideoProbe.validate(temp,requireAudio);
            synchronized(this) {
                ObjectNode doc=uploadAllowed(id,actor,side,digest);
                var uploads=(ObjectNode)access(doc).get("uploads");
                Path previous = dir(id).resolve(doc.path(side).path("file").asText());
                Path target = uploads.has(side) ? raw.resolve(side+"-"+digest+".mp4") : raw.resolve(side+".mp4");
                Files.move(temp,target,ATOMIC_MOVE,REPLACE_EXISTING);
                ((ObjectNode)doc.get(side)).put("file","raw/"+target.getFileName());
                access(doc).withObjectProperty("validated").put(side,true);
                uploads.put(side,digest);
                if(uploads.has("front") && uploads.has("rear")) access(doc).put("phase","READY");
                store(doc);
                if (!previous.equals(target)) Files.deleteIfExists(previous);
                return view(doc);
            }
        } finally {
            try {Files.deleteIfExists(temp);} finally {
                synchronized (this) { reservedUploadBytes-=remaining; uploading.remove(key); stopped(id); }
            }
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
    private ObjectNode participantTrip(String id, UUID actor) throws IOException {
        ObjectNode doc=read(id);
        if(seats(doc).stream().noneMatch(s->actor.equals(s.occupant()))) throw new ResponseStatusException(NOT_FOUND);
        if(Set.of("DISCARDING","DISCARDED").contains(phase(doc))) throw new ResponseStatusException(GONE);
        return doc;
    }
    synchronized DriveMark mark(String id, UUID actor, UUID markId, MarkInput input) throws IOException {
        ObjectNode doc=participantTrip(id,actor);
        if(input==null || input.kind()==null || input.atMs()==null || input.atMs()<0 || input.atMs()>=86_400_000L)
            throw new ResponseStatusException(BAD_REQUEST);
        DriveMark mark=new DriveMark(markId,actor,input.kind(),input.atMs());
        ObjectNode marks=doc.withObjectProperty("marks");
        if(marks.has(markId.toString())) {
            if(!mark.equals(json.treeToValue(marks.get(markId.toString()),DriveMark.class))) throw new ResponseStatusException(CONFLICT);
            return mark;
        }
        if(!Set.of("DRIVING","PENDING","UPLOAD").contains(phase(doc))) throw new ResponseStatusException(CONFLICT);
        if(marks.size()>=24) throw new ResponseStatusException(CONFLICT,"운행당 최대 24개");
        marks.set(markId.toString(),json.valueToTree(mark));
        store(doc);
        return mark;
    }
    synchronized Timeline timeline(String id, UUID actor) throws IOException {
        ObjectNode doc=participantTrip(id,actor);
        boolean canPlay=canPlayMarked(doc,actor);
        List<TimelineEntry> entries=new ArrayList<>();
        for(JsonNode node:doc.path("marks")) {
            DriveMark mark=json.treeToValue(node,DriveMark.class);
            JsonNode clip=access(doc).path("markedClips").path(mark.id().toString());
            String state="MARKED", title=null, url=null;
            if(access(doc).has("draftVersion")) {
                boolean available=clip.has("id") && (!"SAVED".equals(phase(doc)) ||
                        clip.path("id").asText().equals(access(doc).path("selection").path("moment").asText()));
                state=available?"CLIP_READY":"NO_CLIP";
                if(available && !canPlay) state="SHARING_DISABLED";
                if(available && canPlay) {
                    title="SAVED".equals(phase(doc))?access(doc).path("item").path("title").asText():clip.path("title").asText();
                    url="/api/private-trips/"+id+"/marks/"+mark.id()+"/clip";
                }
            }
            String place=markedPlace(doc,mark);
            entries.add(new TimelineEntry(mark,authorName(doc,mark.author()),place,state,title,url));
        }
        entries.sort(Comparator.comparingLong((TimelineEntry e)->e.mark().atMs()).thenComparing(e->e.mark().id()));
        Instant start=access(doc).has("startedAt")?Instant.parse(access(doc).get("startedAt").asText()):null;
        return new Timeline(id,start,phase(doc),entries);
    }
    record NameInput(String displayName) {}
    synchronized NameInput setName(String id,UUID actor,NameInput input)throws IOException {
        ObjectNode doc=participantTrip(id,actor);
        if(input==null || !validName(input.displayName())) throw new ResponseStatusException(BAD_REQUEST);
        if(!"DRIVING".equals(phase(doc))) throw new ResponseStatusException(CONFLICT);
        String name=input.displayName().strip();
        access(doc).withObjectProperty("names").put(actor.toString(),name);store(doc);
        return new NameInput(name);
    }
    private String authorName(ObjectNode doc,UUID actor) {
        String name=access(doc).path("names").path(actor.toString()).asString(null);
        if(name!=null) return name;
        if(actor.toString().equals(access(doc).path("owner").asText())) return "운전자";
        List<Seat> occupants=seats(doc);
        for(int i=0;i<occupants.size();i++) if(occupants.get(i).occupant().equals(actor)) return "탑승자 "+(i+1);
        return "탑승자";
    }
    private String markedPlace(ObjectNode doc, DriveMark mark) {
        long nearest=30001; String label=null;
        for(JsonNode event:doc.path("driveContext").path("events")) {
            String candidate=event.path("label").asString(null);
            if(candidate==null || candidate.isBlank()) continue;
            long delta=Math.abs(event.path("atMs").asLong()-mark.atMs());
            if(delta<nearest) { nearest=delta;label=candidate; }
            else if(delta==nearest && !candidate.equals(label)) label=null;
        }
        return nearest<=30000 ? label : access(doc).path("markPlaces").path(mark.id().toString()).asString(null);
    }
    private boolean canPlayMarked(ObjectNode doc,UUID actor) {
        if(actor.toString().equals(access(doc).path("owner").asText())) return true;
        var receipts=access(doc).path("receipts");
        for(Seat seat:seats(doc)) {
            if(!receipts.path(seat.occupant()+"/PASSENGER").path("shareMarkedClips").asBoolean(false)) return false;
            if(seat.under14() && !receipts.path(seat.occupant()+"/GUARDIAN").path("shareMarkedClips").asBoolean(false)) return false;
        }
        return true;
    }
    synchronized Path markedClip(String id,UUID actor,UUID markId) throws IOException {
        ObjectNode doc=participantTrip(id,actor);
        if(!canPlayMarked(doc,actor)) throw new ResponseStatusException(FORBIDDEN);
        JsonNode clip=access(doc).path("markedClips").path(markId.toString());
        if(!clip.has("id") || !Set.of("READY","SAVED").contains(phase(doc))) throw new ResponseStatusException(NOT_FOUND);
        if("SAVED".equals(phase(doc))) {
            if(!clip.get("id").asText().equals(access(doc).path("selection").path("moment").asText())) throw new ResponseStatusException(NOT_FOUND);
            return dir(id).resolve("saved.mp4");
        }
        return dir(id).resolve("work").resolve(access(doc).get("draftVersion").asText()).resolve(clip.get("id").asText()+".mp4");
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
        boolean priorContext=receipts.path(key).path("useTripContext").asBoolean(false);
        boolean context=yes && (d.useTripContext()==null?priorContext:d.useTripContext());
        boolean priorShare=receipts.path(key).path("shareMarkedClips").asBoolean(false);
        boolean share=yes && (d.shareMarkedClips()==null?priorShare:d.shareMarkedClips());
        if(yes && agreed(receipts.path(key))) {
            if(receipts.path(key).path("useTripContext").asBoolean()==context) {
                if(share!=priorShare) {
                    ((ObjectNode)receipts.get(key)).put("shareMarkedClips",share).put("at",Instant.now().toString());
                    if ("SAVED".equals(phase(doc))) vault("save",doc);
                    store(doc);
                }
                return view(doc);
            }
            if(!context && access(doc).path("usedContext").asBoolean()) {
                receipts.set(key,json.valueToTree(new Receipt(role,occupant,actor,d.extraction(),d.geminiTranscript(),false,Instant.now().toString(),share)));
                return discardContext(doc);
            }
            if(!Set.of("PENDING","UPLOAD").contains(phase(doc))) throw new ResponseStatusException(CONFLICT);
        }
        receipts.set(key,json.valueToTree(new Receipt(role,occupant,actor,d.extraction(),d.geminiTranscript(),context,Instant.now().toString(),share)));
        boolean all=refresh(doc);
        access(doc).put("phase",yes?(all?(access(doc).has("uploads") && access(doc).get("uploads").size()<2?"UPLOAD":"READY"):"PENDING"):"DISCARDING");
        store(doc);cleanup(doc);return view(doc);
    }
    private Trip discardContext(ObjectNode doc) throws IOException {
        access(doc).put("phase","DISCARDING");
        doc.put("audioProcessingConsent",false).put("geminiTranscriptConsent",false);
        store(doc);cleanup(doc);return view(doc);
    }
    private boolean validLabel(String label) {
        return label==null || (label.length()<=64 && label.codePoints().noneMatch(Character::isISOControl));
    }
    synchronized Trip attachContext(String id,UUID actor,DriveContext context) throws IOException {
        owner(id,actor);ObjectNode doc=read(id);
        if(!"UPLOAD".equals(phase(doc))) throw new ResponseStatusException(CONFLICT);
        for(JsonNode receipt:access(doc).get("receipts"))
            if(!receipt.path("useTripContext").asBoolean()) throw new ResponseStatusException(FORBIDDEN);
        if(context==null || !validLabel(context.departure()) || !validLabel(context.destination())
                || context.events()==null || context.events().size()>128) throw new ResponseStatusException(BAD_REQUEST);
        for(DriveEvent event:context.events()) {
            if(event==null || event.atMs()<0 || event.kind()==null || !validLabel(event.label())) throw new ResponseStatusException(BAD_REQUEST);
        }
        doc.set("driveContext",json.valueToTree(context));access(doc).put("usedContext",true);
        store(doc);return view(doc);
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
            var markedClips=access(doc).putObject("markedClips");
            for(JsonNode card:result.path("cards")) {
                if(!Files.isRegularFile(export.resolve("master-"+card.path("file").asText()))) throw new IOException("Protected clip missing");
                for(JsonNode markId:card.path("markIds"))
                    markedClips.putObject(markId.asText()).put("id",card.get("id").asText()).put("title",card.get("title").asText());
            }
            access(doc).put("draftVersion",export.getFileName().toString());
            var places=access(doc).putObject("markPlaces");
            for(JsonNode node:doc.path("marks")) {
                DriveMark m=json.treeToValue(node,DriveMark.class);
                String label=markedPlace(doc,m);
                if(label!=null) places.put(m.id().toString(),label);
            }
            doc.remove("driveContext");
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
                if(Set.of("DRIVING","PENDING","UPLOAD","READY").contains(phase(doc)) && access(doc).has("expiresAt")
                        && !Instant.parse(access(doc).get("expiresAt").asText()).isAfter(Instant.now())) {
                    access(doc).put("phase","DISCARDING");
                    doc.put("audioProcessingConsent",false).put("geminiTranscriptConsent",false);
                    store(doc);
                }
                if ("SAVED".equals(phase(doc)) && !vaultBucket.isBlank() && !access(doc).path("vaultSynced").asBoolean()) {
                    vault("save",doc);
                    access(doc).put("vaultSynced",true);
                    store(doc);
                }
                cleanup(doc);
            }
        }
    }
    synchronized void startEdit(String id) {
        if (uploading.contains(id+"/front") || uploading.contains(id+"/rear")) throw new ResponseStatusException(CONFLICT);
        editing.add(id);
        started(id);
    }
    synchronized void stopEdit(String id) throws IOException {
        stopped(id);
        if (!workers.containsKey(id)) editing.remove(id);
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
        // Recheck old worker outputs against the actual saved interval as well.
        var marked=(ObjectNode)a.get("markedClips");
        if(marked!=null) for(String key:List.copyOf(marked.propertyNames())) {
            if(!selection.moment().equals(marked.path(key).path("id").asText())) continue;
            long at=doc.path("marks").path(key).path("atMs").asLong(-1);
            if(at<card.path("startMs").asLong() || at>=card.path("endMs").asLong()) marked.remove(key);
        }
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
        if ("SAVING".equals(phase)) {
            vault("save",doc);
            if (!vaultBucket.isBlank()) access(doc).put("vaultSynced",true);
        } else if (access(doc).has("item")) {
            vault("revoke",doc);
        }
        deleteTree(folder.resolve("raw"));deleteTree(folder.resolve("work"));deleteTree(folder.resolve("jobs"));
        doc.remove("driveContext");
        if("DISCARDING".equals(phase)) {
            Files.deleteIfExists(folder.resolve("saved.mp4"));access(doc).remove("item");
            doc.remove("marks");access(doc).remove("markedClips");access(doc).remove("markPlaces");
        }
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
