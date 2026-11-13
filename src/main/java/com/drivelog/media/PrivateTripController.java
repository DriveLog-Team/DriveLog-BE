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

    @PostMapping("/private-trips/departures")
    @ApiResponse(responseCode="200",description="현재 운행과 등록된 탑승자",useReturnTypeSchema=true)
    @Operation(operationId="departTrip",summary="출발 시 주행 시계와 참여자 연결",description="startedAt은 차량의 출발 UTC 시각이다. 같은 UUID·시각·명단으로 재시도 가능. 도착 시 같은 sourceId로 기존 운행 등록 API를 호출한다.",security=@SecurityRequirement(name="operatorToken"))
    PrivateTrips.Trip depart(@RequestBody PrivateTrips.Departure body,HttpServletRequest req)throws IOException {
        media.authorize(req);
        return store.depart(body);
    }

    record VoiceMark(Long atMs,String command) {}
    @PutMapping("/private-trips/{id}/voice-marks/{markId}")
    @ApiResponse(responseCode="200",description="저장된 표시. 차량은 ‘표시했어요’로 응답할 수 있다.",useReturnTypeSchema=true)
    @ApiResponse(responseCode="422",description="지원하지 않는 음성 명령",content=@Content)
    @Operation(operationId="markByVoice",summary="차량에서 인식한 한국어 저장 명령으로 순간 표시",description="음성 인식 결과만 전송한다. 명령 원문은 보관하지 않는다.",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.DriveMark voice(@PathVariable String id,@PathVariable UUID markId,@RequestBody VoiceMark body,HttpServletRequest req)throws IOException {
        UUID actor=store.person(req);
        if(body==null || body.command()==null || body.command().length()>160)
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST);
        String command=body.command().replaceAll("[\\s.,!?~]","");
        var match=java.util.regex.Pattern.compile("(?:방금(?:거|꺼|장면|순간)?|지금(?:이?순간|장면)?|이(?:순간|장면))?(?:저장해(?:줘|주세요)?|남겨(?:줘|주세요)?|표시해(?:줘|주세요)?)(다같이웃는데까지|웃는데까지|웃을때까지)?").matcher(command);
        if(!match.matches()) throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var kind=match.group(1)==null?PrivateTrips.MarkKind.VOICE:PrivateTrips.MarkKind.VOICE_UNTIL_LAUGHTER;
        return store.mark(id,actor,markId,new PrivateTrips.MarkInput(kind,body.atMs()));
    }

    @PutMapping("/private-trips/{id}/marks/{markId}")
    @ApiResponse(responseCode="200",description="같은 키·입력은 같은 표시 반환",useReturnTypeSchema=true)
    @ApiResponse(responseCode="409",description="키 충돌, 마감 또는 표시 한도 도달",content=@Content)
    @Operation(operationId="markDriveMoment",summary="탑승자 폰 버튼 또는 차량 음성 인텐트로 순간 표시",description="atMs는 주행 시작부터의 밀리초다. actor는 개인 토큰에서 결정한다.",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.DriveMark mark(@PathVariable String id,@PathVariable UUID markId,@RequestBody PrivateTrips.MarkInput body,HttpServletRequest req)throws IOException {
        return store.mark(id,store.person(req),markId,body);
    }
    @GetMapping("/private-trips/{id}/timeline")
    @ApiResponse(responseCode="200",description="시간순 표시, 선택적 장소명 및 재생 주소",useReturnTypeSchema=true)
    @Operation(operationId="getDriveTimeline",summary="같은 주행에 참여한 탑승자의 타임라인",security=@SecurityRequirement(name="personToken"))
    PrivateTrips.Timeline timeline(@PathVariable String id,HttpServletRequest req)throws IOException {
        return store.timeline(id,store.person(req));
    }
    @GetMapping(value="/private-trips/{id}/marks/{markId}/clip",produces="video/mp4")
    @ApiResponse(responseCode="200",description="보호 처리된 표시 클립",content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="206",description="부분 영상",content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @Operation(operationId="getMarkedClip",summary="표시된 순간의 보호 클립 재생",description="소유자 또는 전원 공유 동의를 받은 같은 운행 탑승자.",security=@SecurityRequirement(name="personToken"))
    FileSystemResource markedClip(@PathVariable String id,@PathVariable UUID markId,HttpServletRequest req)throws IOException {
        return new FileSystemResource(store.markedClip(id,store.person(req),markId));
    }

    @PostMapping("/private-trips")
    @ApiResponse(responseCode="200",description="등록된 운행과 동의 상태",useReturnTypeSchema=true)
    @Operation(operationId="registerPrivateTrip",summary="파일 없이 운행·탑승자·보호자 등록",description="sourceId는 차량이 생성한 운행 UUID다. source에 정차 상태·카메라 시각을 보낸다. 같은 sourceId와 명단으로 재요청할 수 있다. 최초 동의는 모두 미응답.",security=@SecurityRequirement(name="operatorToken"))
    PrivateTrips.Trip register(@RequestBody PrivateTrips.Registration body,HttpServletRequest req)throws IOException {
        media.authorize(req);return store.register(body);
    }
    @PutMapping(value="/private-trips/{id}/media/{side}",consumes="video/mp4")
    @Operation(operationId="uploadConsentedTrack",summary="UPLOAD 상태에서 소유자가 전방 또는 후방 MP4 전송",
            description="side는 front 또는 rear. Content-Length와 X-Content-SHA256(소문자 hex)이 필요하다. 각 파일은 최대 8 GiB. 영상 스트림과 지정한 쪽 오디오를 검사한다. 두 파일 수락 시 READY. 편집 시작 전 같은 side만 교체할 수 있으며 검증 실패 시 기존 파일을 유지한다.",
            security=@SecurityRequirement(name="personToken"))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(mediaType="video/mp4",schema=@Schema(type="string",format="binary")))
    @ApiResponse(responseCode="200",description="업로드 반영 후 운행 상태",useReturnTypeSchema=true)
    @ApiResponse(responseCode="400",description="길이 또는 SHA-256 불일치",content=@Content)
    @ApiResponse(responseCode="409",description="같은 트랙 전송 중, 편집 진행 또는 초안 확정",content=@Content)
    @ApiResponse(responseCode="422",description="읽을 수 없는 영상·지정 오디오 없음·규격 초과",content=@Content)
    @ApiResponse(responseCode="507",description="다른 업로드 예약과 편집 여유를 제외한 디스크 용량 부족",content=@Content)
    PrivateTrips.Trip upload(@PathVariable String id,@PathVariable String side,@RequestHeader("X-Content-SHA256") String sha,HttpServletRequest req) throws IOException {
        return store.upload(id,store.person(req),side,sha,req.getContentLengthLong(),req.getInputStream());
    }
    @PutMapping("/private-trips/{id}/context")
    @Operation(operationId="attachDriveContext",summary="전원 위치 맥락 동의 후 출발·도착지 및 운행 이벤트 등록",security=@SecurityRequirement(name="personToken"))
    @ApiResponse(responseCode="200",description="맥락 등록 후 운행 상태",useReturnTypeSchema=true)
    PrivateTrips.Trip context(@PathVariable String id,@RequestBody PrivateTrips.DriveContext context,HttpServletRequest req)throws IOException {
        return store.attachContext(id,store.person(req),context);
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
    @Operation(operationId="finalizeTrip",summary="선택한 편집본을 개인 보관함에 저장하고 운행 원본 파기",description="READY인 작업에서 jobId와 moment를 지정한다. 같은 선택으로 재호출 가능. 운영에서 SAVED는 선택본 백업과 원본·중간물 삭제 완료를 뜻한다. 백업 장애 시 같은 선택으로 재시도한다.",security=@SecurityRequirement(name="personToken"))
    @ApiResponse(responseCode="503",description="보관함 백업 일시 실패. 같은 선택으로 재시도",content=@Content)
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
