package com.drivelog.media;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.*;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Single-operator media acceptance environment. Only locally registered trips are processed. */
@RestController
@Profile("media")
@RequestMapping("/api/media/jobs")
@Tag(name="정차 후 미디어 작업")
@SecurityRequirement(name="operatorToken")
@SecurityRequirement(name="personToken")
@ApiResponse(responseCode="401", description="HTTPS 팀 서버 Bearer 인증 필요", content=@Content)
@ApiResponse(responseCode="403", description="탑승자·보호자 또는 분석 동의 누락", content=@Content)
@ApiResponse(responseCode="409", description="정차 조건 또는 도로 검토 상태 확인 필요", content=@Content)
public class MediaJobController {
    public record Request(@Schema(requiredMode=Schema.RequiredMode.REQUIRED, pattern="[A-Za-z0-9_-]{1,80}", example="demo") String tripId,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED, allowableValues={"STORY_15","SHORT_30"}) String profile,
            @Schema(description="편집할 READY 작업 ID. edit와 함께 지정") UUID baseJob, Edit edit) {}
    public record Edit(@Schema(requiredMode=Schema.RequiredMode.REQUIRED,example="moment-002") String id,
            @Schema(description="원본 운행 기준 밀리초") Long startMs, Long endMs,
            @Schema(maxLength=80) String title, @Schema(description="기준 카드의 발화 개수와 순서대로 입력. 개별 자막 최대 200자") List<String> captions) {}
    public record Job(UUID id, String tripId, String profile, String state, Path manifest, Path export, JsonNode roadReview) {}
    private final Map<UUID, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ThreadPoolExecutor runner = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
    private final JsonMapper json = JsonMapper.builder().build();
    private final Path trips, library, script;
    final PrivateTrips privateTrips;
    private final String python;
    private final byte[] secret;

    public MediaJobController(@Value("${media.trips}") Path trips, @Value("${media.library}") Path library,
            @Value("${media.worker}") Path script, @Value("${media.python:python}") String python,
            @Value("${media.token}") String token, PrivateTrips privateTrips) throws IOException {
        if (token.length() < 32) throw new IllegalArgumentException("media.token requires 32 or more characters");
        this.privateTrips=privateTrips;
        this.secret = token.getBytes(StandardCharsets.UTF_8);
        this.trips = trips.toRealPath();
        this.library = Files.createDirectories(library).toRealPath();
        this.script = script.toRealPath();
        this.python = python;
    }

    void authorize(HttpServletRequest request) throws IOException {
        String header = request.getHeader("Authorization");
        if (!request.isSecure() || header == null ||
                !header.startsWith("Bearer ") || !MessageDigest.isEqual(secret,
                header.substring(7).getBytes(StandardCharsets.UTF_8))) throw error(HttpStatus.UNAUTHORIZED);
    }

    List<String> availableTrips(HttpServletRequest request) throws IOException {
        authorize(request);
        try (var children=Files.list(trips)) {
            return children.filter(p -> p.getFileName().toString().matches("[A-Za-z0-9_-]{1,80}"))
                .filter(p -> Files.isRegularFile(p.resolve("trip.json")))
                .map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @PostMapping("/{id}/edits")
    @Operation(operationId="editMediaClip",summary="READY 추천 카드의 제목·자막·구간을 수정해 새 편집본 생성")
    @ApiResponse(responseCode="202",description="새 jobId로 상태 조회",content=@Content(schema=@Schema(implementation=MediaContract.Accepted.class)))
    @ApiResponse(responseCode="400",description="잘못된 편집 요청",content=@Content)
    @ApiResponse(responseCode="404",description="카드 또는 기준 작업 없음",content=@Content)
    public ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody Edit edit, HttpServletRequest req) throws IOException {
        Job base=owned(id,req);
        return submit(new Request(base.tripId(),base.profile(),id,edit),req);
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="application/json",
            schema=@Schema(implementation=Request.class),examples={
            @ExampleObject(name="추천 요청",value="{\"tripId\":\"demo\",\"profile\":\"STORY_15\"}"),
            @ExampleObject(name="자막 편집",value="{\"tripId\":\"demo\",\"profile\":\"STORY_15\",\"baseJob\":\"a22e6666-e52c-483f-ac84-a12f64d20200\",\"edit\":{\"id\":\"moment-002\",\"captions\":[\"눈 뜨면 내가 제일 먼저 보일 거야.\"]}}")}))
    @Operation(operationId="submitMediaJob", summary="운행 추천 또는 기존 카드 편집 요청", description="202는 대기열 등록을 뜻한다. 반환된 jobId로 처리 상태를 조회한다.")
    @ApiResponse(responseCode="202", description="작업 등록", content=@Content(schema=@Schema(implementation=MediaContract.Accepted.class)))
    @ApiResponse(responseCode="400", description="운행·프로필·편집 조합 오류", content=@Content)
    @ApiResponse(responseCode="404", description="운행 또는 기준 카드 없음", content=@Content)
    @ApiResponse(responseCode="429", description="실행 1건·대기 4건이 모두 사용 중", content=@Content)
    public ResponseEntity<?> submit(@RequestBody Request input, HttpServletRequest req) throws IOException {
        synchronized(privateTrips) { return registerJob(input,req); }
    }

    private ResponseEntity<?> registerJob(Request input,HttpServletRequest req) throws IOException {
        boolean personal=PrivateTrips.managed(input.tripId());
        if(!personal) authorize(req);
        if (input.tripId() == null || !input.tripId().matches("[A-Za-z0-9_-]{1,80}") ||
                !("STORY_15".equals(input.profile()) || "SHORT_30".equals(input.profile())))
            throw error(HttpStatus.BAD_REQUEST);
        if (input.edit()!=null && ((input.edit().title()!=null && (input.edit().title().isBlank() || input.edit().title().length()>80))
                || (input.edit().captions()!=null && (input.edit().captions().size()>100 || input.edit().captions().stream().anyMatch(t -> t==null || t.length()>200))))) throw error(HttpStatus.BAD_REQUEST);
        if (Files.getFileStore(library).getUsableSpace()<2_000_000_000L) throw error(HttpStatus.INSUFFICIENT_STORAGE);
        Path file = personal ? privateTrips.input(input.tripId(),privateTrips.person(req)) : trips.resolve(input.tripId()).resolve("trip.json");
        if (!Files.isRegularFile(file) || (!personal && !file.toRealPath().startsWith(trips))) throw error(HttpStatus.NOT_FOUND);
        JsonNode manifest = json.readTree(file.toFile());
        if (!input.tripId().equals(manifest.path("tripId").asText())) throw error(HttpStatus.CONFLICT);
        consent(file);
        if ((input.baseJob()==null) != (input.edit()==null)) throw error(HttpStatus.BAD_REQUEST);
        String baseVersion = null;
        if (input.edit()!=null) {
            Job parent = jobs.get(input.baseJob());
            if (parent==null || !"READY".equals(parent.state()) || !parent.tripId().equals(input.tripId()) ||
                    !parent.profile().equals(input.profile())) throw error(HttpStatus.CONFLICT);
            if (input.edit().id()==null || !input.edit().id().matches("moment-[0-9]{3,}") ||
                    !Files.isRegularFile(parent.export().resolve(input.edit().id()+".mp4"))) throw error(HttpStatus.NOT_FOUND);
            baseVersion = parent.export().getFileName().toString();
        }
        UUID id = UUID.randomUUID();
        Job job = new Job(id,input.tripId(),input.profile(),"QUEUED",file,null,manifest.path("roadReview"));
        synchronized(jobs) {
            if (jobs.size() >= 64) jobs.values().stream().filter(j -> !Set.of("RUNNING","QUEUED").contains(j.state()))
                    .findFirst().ifPresent(j -> jobs.remove(j.id()));
            jobs.put(id,job);
        }
        String revision = baseVersion;
        if(personal) privateTrips.started(job.tripId());
        try { runner.execute(() -> process(job,input.edit(),revision)); }
        catch (RejectedExecutionException e) { if(personal) privateTrips.stopped(job.tripId()); jobs.remove(id); throw error(HttpStatus.TOO_MANY_REQUESTS); }
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("jobId",id));
    }

    private void process(Job job, Edit edit, String baseVersion) {
        Path editPath = null;
        Process process = null;
        Path completed=null;
        try {
            Path outputRoot=PrivateTrips.managed(job.tripId())?privateTrips.work(job.tripId()):library;
            consent(job.manifest());
            update(job,"RUNNING",null);
            List<String> command = new ArrayList<>(List.of(python,script.toString(),job.manifest().toString(),
                    outputRoot.toString(),"--profile",job.profile()));
            if (edit != null) {
                editPath = Files.createTempFile(outputRoot,"edit-",".json");
                var data = json.valueToTree(edit).deepCopy();
                var obj = (tools.jackson.databind.node.ObjectNode)data;
                for (String key : List.of("startMs","endMs","title","captions")) if (obj.path(key).isNull()) obj.remove(key);
                obj.put("baseVersion",baseVersion);
                json.writeValue(editPath.toFile(),obj);
                command.addAll(List.of("--edit",editPath.toString()));
            }
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(60,TimeUnit.MINUTES) || process.exitValue()!=0) throw new IOException("Worker failed");
            String result = new String(process.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).strip();
            Path output = outputRoot.resolve(result).toRealPath();
            if (!outputRoot.equals(output.getParent()) || !Files.isRegularFile(output.resolve("cards.json")))
                throw new IOException("Invalid worker output");
            consent(job.manifest());
            completed=output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); update(job,"FAILED",null);
        } catch (Exception e) { update(job,"FAILED",null); }
        finally {
            if (process!=null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            }
            if (editPath!=null) try { Files.deleteIfExists(editPath); } catch (IOException ignored) {}
            synchronized(privateTrips) {
                if(PrivateTrips.managed(job.tripId())) try { privateTrips.stopped(job.tripId()); }
                catch(IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Pending trip cleanup",e); }
                if(completed!=null) update(job,"READY",completed);
            }
        }
    }

    private void update(Job job,String state,Path output) {
        jobs.put(job.id(),new Job(job.id(),job.tripId(),job.profile(),state,job.manifest(),output,job.roadReview()));
    }
    private void consent(Path manifest) throws IOException {
        JsonNode spec = json.readTree(manifest.toFile());
        JsonNode receipt = spec.path("extraction");
        if (!"P".equals(receipt.path("gear").asText()) || !confirmed(receipt, "stationary"))
            throw error(HttpStatus.CONFLICT);
        JsonNode people = receipt.path("occupants");
        JsonNode count = receipt.path("occupantCount");
        if (!confirmed(spec, "audioProcessingConsent") || !people.isArray() || people.isEmpty()
                || !count.isIntegralNumber() || count.asLong() != people.size()) throw error(HttpStatus.FORBIDDEN);
        for (JsonNode person : people) {
            String age = person.path("ageBand").asText();
            if (!confirmed(person, "allowed") || !Set.of("14plus", "under14").contains(age)
                    || ("under14".equals(age) && !confirmed(person, "guardianAllowed")))
                throw error(HttpStatus.FORBIDDEN);
        }
        if ("gleo-demo".equals(spec.path("recommendationMode").asText()) && !confirmed(spec, "geminiTranscriptConsent"))
            throw error(HttpStatus.FORBIDDEN);
        if (!confirmed(spec.path("roadReview"), "complete")) throw error(HttpStatus.CONFLICT);
    }
    private static boolean confirmed(JsonNode node, String key) {
        return node.path(key).isBoolean() && node.path(key).asBoolean();
    }
    Job owned(UUID id,HttpServletRequest req) throws IOException {
        Job job=jobs.get(id);
        if (job==null) throw error(HttpStatus.NOT_FOUND);
        if(PrivateTrips.managed(job.tripId())) privateTrips.input(job.tripId(),privateTrips.person(req)); else authorize(req);
        validateJobAccess(job);
        return job;
    }
    private void validateJobAccess(Job job) throws IOException {
        consent(job.manifest());
        if (!job.roadReview().equals(json.readTree(job.manifest().toFile()).path("roadReview")))
            throw error(HttpStatus.CONFLICT);
    }
    @GetMapping("/{id}")
    @Operation(operationId="getMediaJob", summary="작업 상태와 추천 카드 조회")
    @ApiResponse(responseCode="200", description="작업 상태. 후보 0개도 READY로 완료될 수 있다.", content=@Content(schema=@Schema(implementation=MediaContract.Status.class)))
    @ApiResponse(responseCode="404", description="등록되지 않은 작업", content=@Content)
    public ResponseEntity<?> status(@PathVariable UUID id,HttpServletRequest req) throws IOException {
        Job job=owned(id,req);
        Map<String,Object> result=new LinkedHashMap<>(Map.of("jobId",id,"state",job.state()));
        if (job.export()!=null) result.put("result",json.readTree(job.export().resolve("cards.json").toFile()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
    @GetMapping("/{id}/clips/{moment}")
    @Operation(operationId="getMediaClip", summary="선택한 버전의 MP4 보기")
    @ApiResponse(responseCode="200", description="영상 전체", content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="206", description="Range에 해당하는 영상 바이트", content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="416", description="지원하지 않는 바이트 범위", content=@Content)
    @ApiResponse(responseCode="404", description="카드가 준비되지 않았거나 없음", content=@Content)
    public ResponseEntity<FileSystemResource> video(@PathVariable UUID id,@PathVariable String moment,
            HttpServletRequest req) throws IOException {
        Job job=owned(id,req);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.valueOf("video/mp4"))
                .body(new FileSystemResource(clip(job, moment)));
    }

    Path clip(Job job, String moment) throws IOException {
        validateJobAccess(job);
        if (job.export()==null || !moment.matches("moment-[0-9]{3,}")) throw error(HttpStatus.NOT_FOUND);
        Path file=job.export().resolve(moment+".mp4");
        if (!Files.isRegularFile(file)) throw error(HttpStatus.NOT_FOUND);
        return file;
    }

    /** Contract for the vehicle app to forward after a Gleo Action in the selected trip. */
    @GetMapping("/{id}/gleo-callback")
    @Operation(operationId="getGleoCallback", summary="차량 앱이 전달할 Gleo 콜백 구성")
    @ApiResponse(responseCode="200", description="voice 모드 콜백", content=@Content(schema=@Schema(implementation=MediaContract.Callback.class)))
    @ApiResponse(responseCode="400", description="지원하지 않는 주제", content=@Content)
    @ApiResponse(responseCode="404", description="작업 없음", content=@Content)
    public ResponseEntity<?> gleoCallback(@PathVariable UUID id, @Parameter(schema=@Schema(allowableValues={"감사와 위로","함께한 추억","처음과 성장","다시 하고 싶은 약속","함께 웃은 순간"})) @RequestParam(required = false) String theme,
            HttpServletRequest req) throws IOException {
        Job job = owned(id, req);
        JsonNode allowed = json.readTree(job.manifest().toFile()).path("gleoExcerptConsent");
        if (!allowed.isBoolean() || !allowed.asBoolean()) throw error(HttpStatus.FORBIDDEN);
        if (theme != null && !Set.of("감사와 위로", "함께한 추억", "처음과 성장",
                "다시 하고 싶은 약속", "함께 웃은 순간").contains(theme)) throw error(HttpStatus.BAD_REQUEST);
        String status = "success";
        String message;
        List<Map<String, String>> excerpts = new ArrayList<>();
        if (job.export() == null) {
            status = "fail";
            message = "FAILED".equals(job.state()) ? "The highlights could not be prepared."
                    : "The highlights are still being prepared.";
        } else {
            JsonNode cards = json.readTree(job.export().resolve("cards.json").toFile()).path("cards");
            for (JsonNode card : cards) {
                JsonNode reason = card.path("recommendation");
                if (!reason.hasNonNull("category") || card.path("cues").isEmpty()) continue;
                if (theme != null && !theme.equals(reason.path("category").asText())) continue;
                excerpts.add(Map.of("momentId", card.path("id").asText(),
                        "theme", reason.path("category").asText(),
                        "excerpt", reason.path("quote").asText()));
            }
            message = excerpts.isEmpty() ? "No conversation highlights were found."
                    : "These are the recommended conversations in order of relevance.";
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                Map.of("functionName", "FIND_CONVERSATION_HIGHLIGHTS", "status", status,
                        "mode", "voice", "message", message, "data", json.writeValueAsString(excerpts)));
    }
    private static ResponseStatusException error(HttpStatus status) { return new ResponseStatusException(status); }
    @PreDestroy public void close() { runner.shutdownNow(); }
}
