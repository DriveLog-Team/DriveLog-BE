package com.drivelog.media;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("media")
@Tag(name="휴대폰 영상 인계")
public class HandoffController {
    @Schema(requiredProperties={"id","expiresAt","qrImage"})
    public record Ticket(UUID id, Instant expiresAt,
            @Schema(description="QR 이미지의 data:image/png;base64 URL") String qrImage) {}
    public record Redemption(@Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode=Schema.RequiredMode.REQUIRED, minLength=43, maxLength=43) String code) {}
    private record Grant(MediaJobController.Job job, String savedTrip, UUID owner, String moment, byte[] secret, Instant expires) {}
    private final MediaJobController media;
    private final Map<UUID,Grant> grants = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final String origin;
    private final Duration lifetime;

    public HandoffController(MediaJobController media, @Value("${media.handoff-origin:}") String origin,
            @Value("${media.handoff-ttl:PT5M}") Duration lifetime) {
        this.media = media;
        if (origin.isBlank()) throw new IllegalArgumentException("Public HTTPS origin is required");
        {
            URI uri = URI.create(origin);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().matches("/?"))
                throw new IllegalArgumentException("Use a public HTTPS origin for phone handoff");
        }
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Handoff lifetime must be positive and at most five minutes");
        this.lifetime = lifetime;
        this.origin = origin.replaceFirst("/$", "");
    }

    @PostMapping("/api/media/jobs/{jobId}/clips/{moment}/handoffs")
    @Operation(operationId="issueHandoff", summary="카드 1개의 일회용 다운로드 QR 발급",
            security={@SecurityRequirement(name="operatorToken"),@SecurityRequirement(name="personToken")})
    @ApiResponse(responseCode="201", description="QR과 회수 ID", content=@Content(schema=@Schema(implementation=Ticket.class)))
    @ApiResponse(responseCode="401", description="운영자 인증 필요", content=@Content)
    @ApiResponse(responseCode="403", description="동의 누락", content=@Content)
    @ApiResponse(responseCode="404", description="작업 또는 클립 없음", content=@Content)
    @ApiResponse(responseCode="409", description="정차 또는 검토 상태 변경", content=@Content)
    @ApiResponse(responseCode="429", description="활성 QR 한도 초과", content=@Content)
    public synchronized ResponseEntity<Ticket> issue(@PathVariable UUID jobId, @PathVariable String moment,
            HttpServletRequest req) throws IOException, WriterException {
        var job = media.owned(jobId, req);
        media.clip(job, moment);
        UUID owner=PrivateTrips.managed(job.tripId()) ? media.privateTrips.person(req) : null;
        return grant(job,null,owner,moment);
    }

    @PostMapping("/api/library/{tripId}/handoffs")
    @Operation(summary="개인 보관 영상의 일회용 QR 발급",security=@SecurityRequirement(name="personToken"))
    @ApiResponse(responseCode="201",description="5분간 유효한 QR",content=@Content(schema=@Schema(implementation=Ticket.class)))
    public synchronized ResponseEntity<Ticket> savedQr(@PathVariable String tripId,HttpServletRequest req) throws IOException, WriterException {
        UUID owner=media.privateTrips.person(req);media.privateTrips.saved(tripId,owner);
        return grant(null,tripId,owner,"saved");
    }

    private ResponseEntity<Ticket> grant(MediaJobController.Job job,String savedTrip,UUID owner,String moment) throws IOException,WriterException {
        Instant now = Instant.now();
        grants.values().removeIf(g -> !g.expires().isAfter(now));
        if (grants.size() >= 128) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
        UUID id = UUID.randomUUID();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String base = origin;
        String qr = qrImage(base + "/api/handoffs/open#" + id + "." + code);
        Instant expires = now.plus(lifetime);
        grants.put(id, new Grant(job,savedTrip,owner,moment,secret,expires));
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new Ticket(id, expires, qr));
    }

    private static String qrImage(String url) throws IOException, WriterException {
        var matrix = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 320, 320);
        var image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, matrix.get(x,y) ? 0 : 0xffffff);
        }
        var png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    }

    @DeleteMapping("/api/media/handoffs/{id}")
    @Operation(operationId="revokeHandoff", summary="발급한 QR 회수", security={@SecurityRequirement(name="operatorToken"),@SecurityRequirement(name="personToken")})
    @ApiResponse(responseCode="204", description="회수 완료. 이미 사용·만료된 ID도 동일", content=@Content)
    @ApiResponse(responseCode="401", description="운영자 인증 필요", content=@Content)
    public synchronized ResponseEntity<Void> revoke(@PathVariable UUID id, HttpServletRequest req) throws IOException {
        Grant grant=grants.get(id);
        if(grant==null) {
            try {media.authorize(req);} catch(ResponseStatusException e){media.privateTrips.person(req);}
        } else if(grant.owner()==null) media.authorize(req);
        else if(!grant.owner().equals(media.privateTrips.person(req))) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        grants.remove(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value="/api/handoffs/open", produces=MediaType.TEXT_HTML_VALUE)
    @Operation(operationId="openHandoff", summary="QR로 선택한 클립을 내려받거나 Android 스토리 앱에 전달", description="페이지를 열 때는 QR을 소비하지 않습니다. 설치된 휴대폰 앱이나 다운로드 폼이 같은 일회용 코드를 교환합니다.")
    public ResponseEntity<String> open() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer")
                .header("X-Frame-Options","DENY").body("""
                <!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>DriveLog · 영상 공유</title>
                <style>body{margin:0;background:#fbfaf7;color:#20362e;font:17px/1.6 system-ui,sans-serif}main{max-width:32rem;margin:12vh auto;padding:1.5rem}
                h1{font-size:2rem}a,button{font:inherit}#story,button{display:block;width:100%;box-sizing:border-box;padding:.8rem;margin:.7rem 0;border-radius:.5rem;text-align:center}
                #story{background:#205447;color:white;text-decoration:none}button{background:white;border:1px solid #607068;color:inherit}#story[hidden]{display:none}</style>
                <main><h1>선택한 영상 공유</h1><p>QR은 5분간 유효하며 영상 한 편을 한 번 받을 수 있습니다.</p>
                <a id="story" hidden>인스타 스토리에 공유</a>
                <form action="/api/handoffs/redeem" method="post"><input type="hidden" name="id"><input type="hidden" name="code">
                <button disabled>MP4 내려받기</button></form><p role="status"></p></main><script src="/api/handoff.js" defer></script></html>""");
    }

    @PostMapping(value="/api/handoffs/redeem",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces="video/mp4")
    @Operation(operationId="redeemHandoff", summary="QR을 한 번 사용하여 MP4 내려받기",
            description="다운로드 폼 또는 DriveLog Android 앱이 폼 본문에 id와 code를 전송한다. 동시에 요청해도 한 번만 허용한다.")
    @ApiResponse(responseCode="200",description="MP4 첨부파일",content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="400",description="폼 값 누락 또는 UUID 오류",content=@Content)
    @ApiResponse(responseCode="410",description="코드 불일치·만료·회수·사용 완료",content=@Content)
    @ApiResponse(responseCode="403",description="동의 철회",content=@Content)
    @ApiResponse(responseCode="409",description="정차 또는 도로 검토 상태 변경",content=@Content)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(
            mediaType=MediaType.APPLICATION_FORM_URLENCODED_VALUE,schema=@Schema(implementation=Redemption.class)))
    public synchronized ResponseEntity<FileSystemResource> redeem(
            @io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam UUID id,
            @io.swagger.v3.oas.annotations.Parameter(hidden=true) @RequestParam String code)
            throws IOException {
        Grant grant = grants.get(id);
        if (grant == null || !grant.expires().isAfter(Instant.now())) {
            grants.remove(id);
            throw new ResponseStatusException(HttpStatus.GONE);
        }
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(grant.secret());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), code.getBytes(StandardCharsets.US_ASCII)))
            throw new ResponseStatusException(HttpStatus.GONE);
        Path clip = grant.savedTrip()==null ? media.clip(grant.job(), grant.moment()) : media.privateTrips.saved(grant.savedTrip(),grant.owner());
        grants.remove(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.valueOf("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"DriveLog-" + grant.moment() + ".mp4\"")
                .body(new FileSystemResource(clip));
    }
}
