package com.drivelog.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("media")
@RequestMapping("/api")
@Tag(name="개인 운행 동의와 보관함")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="HTTPS 및 작업에 맞는 자격증명 필요",content=@io.swagger.v3.oas.annotations.media.Content)
class PrivateTripController {
    private final PrivateTrips store;
    private final MediaJobController media;
    PrivateTripController(PrivateTrips store,MediaJobController media){this.store=store;this.media=media;}
    @ModelAttribute void noCache(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId="issuePersonalAccount",summary="인증한 사용자에게 개인 자격증명 최초 발급",description="신뢰된 팀 서버가 발급하고 자기 사용자 계정에 연결한다. accessToken은 최초 한 번만 반환한다.",security=@SecurityRequirement(name="operatorToken"))
    PrivateTrips.Account issue(HttpServletRequest req)throws IOException {media.authorize(req);return store.issue();}

    @PostMapping("/private-trips")
    @ApiResponse(responseCode="200",description="등록된 운행과 동의 상태",useReturnTypeSchema=true)
    @Operation(operationId="registerPrivateTrip",summary="파일 없이 운행·탑승자·보호자 등록",description="sourceId는 차량이 생성한 운행 UUID다. source에 정차 상태·카메라 시각을 보낸다. 같은 sourceId와 명단으로 재요청할 수 있다. 최초 동의는 모두 미응답.",security=@SecurityRequirement(name="operatorToken"))
    PrivateTrips.Trip register(@RequestBody PrivateTrips.Registration body,HttpServletRequest req)throws IOException {
        media.authorize(req);return store.register(body);
    }
    @PutMapping(value="/private-trips/{id}/media/{side}",consumes="video/mp4")
    @Operation(operationId="uploadConsentedTrack",summary="UPLOAD 상태에서 소유자가 전방 또는 후방 MP4 전송",
            description="side는 front 또는 rear. Content-Length와 X-Content-SHA256(소문자 hex)이 필요하다. 두 파일이 확인되면 READY.",
            security=@SecurityRequirement(name="personToken"))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="200",description="업로드 반영 후 운행 상태",useReturnTypeSchema=true)
    PrivateTrips.Trip upload(@PathVariable String id,@PathVariable String side,@RequestHeader("X-Content-SHA256") String sha,HttpServletRequest req) throws IOException {
        return store.upload(id,store.person(req),side,sha,req.getContentLengthLong(),req.getInputStream());
    }
    @GetMapping("/private-trips")
    @ApiResponse(responseCode="200",description="본인에게 배정된 운행 배열",useReturnTypeSchema=true)
    @Operation(operationId="listAssignedTrips",summary="본인이 탑승자 또는 보호자인 운행 목록",security=@SecurityRequirement(name="personToken"))
    List<PrivateTrips.Trip> assigned(HttpServletRequest req)throws IOException {return store.assigned(store.person(req));}
    @GetMapping("/private-trips/{id}")
    @ApiResponse(responseCode="200",description="운행 상태와 역할별 동의 영수증",useReturnTypeSchema=true)
    @Operation(operationId="getConsentStatus",summary="해당 운행 동의 현황·원본 파기 상태 조회",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.Trip state(@PathVariable String id,HttpServletRequest req)throws IOException {return store.status(id,store.person(req));}
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="403",description="지정된 응답자만 동의를 제출할 수 있음",content=@io.swagger.v3.oas.annotations.media.Content)
    @PutMapping("/private-trips/{id}/consents/{occupant}/{role}")
    @ApiResponse(responseCode="200",description="반영된 응답과 운행 상태",useReturnTypeSchema=true)
    @Operation(operationId="recordConsent",summary="지정된 본인 또는 보호자의 동의·거절·철회",description="extraction과 geminiTranscript 모두 명시한다. 하나라도 false면 처리를 취소하고 원본과 중간물, 보관된 편집본의 삭제를 시작한다.",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.Trip consent(@PathVariable String id,@PathVariable UUID occupant,@PathVariable PrivateTrips.Role role,
            @RequestBody PrivateTrips.Decision body,HttpServletRequest req)throws IOException {
        return store.decide(id,occupant,role,store.person(req),body);
    }
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="409",description="작업 진행 중 또는 확정 대상 충돌",content=@io.swagger.v3.oas.annotations.media.Content)
    @PostMapping("/private-trips/{id}/finalize")
    @ApiResponse(responseCode="200",description="선택본 저장과 원본 정리 후 운행 상태",useReturnTypeSchema=true)
    @Operation(operationId="finalizeTrip",summary="선택한 편집본을 개인 보관함에 저장하고 운행 원본 파기",description="READY인 작업에서 jobId와 moment를 지정한다. 같은 선택으로 재호출 가능. SAVED는 원본·중간물 삭제 완료를 뜻한다.",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.Trip finish(@PathVariable String id,@RequestBody PrivateTrips.Selection body,HttpServletRequest req)throws IOException {
        return store.finish(id,store.person(req),body,media,req);
    }
    @DeleteMapping("/private-trips/{id}")
    @ApiResponse(responseCode="200",description="폐기 상태",useReturnTypeSchema=true)
    @Operation(operationId="discardTrip",summary="저장 없이 운행 폐기 또는 보관 영상 삭제",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.Trip discard(@PathVariable String id,HttpServletRequest req)throws IOException {return store.discard(id,store.person(req));}
    @GetMapping("/library")
    @ApiResponse(responseCode="200",description="본인의 저장 영상 배열",useReturnTypeSchema=true)
    @Operation(operationId="listLibrary",summary="본인의 보관함을 최근 저장순으로 조회",security=@SecurityRequirement(name="personToken"))
    List<PrivateTrips.Item> library(HttpServletRequest req)throws IOException {return store.items(store.person(req));}
    @GetMapping(value="/library/{id}/clip",produces="video/mp4")
    @ApiResponse(responseCode="200",description="보관 MP4",content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="206",description="요청한 바이트 범위",content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @Operation(operationId="getLibraryClip",summary="원본 파기 후에도 본인 보관 영상을 재생",security=@SecurityRequirement(name="personToken"))
    FileSystemResource video(@PathVariable String id,HttpServletRequest req)throws IOException {
        return new FileSystemResource(store.saved(id,store.person(req)));
    }
}
