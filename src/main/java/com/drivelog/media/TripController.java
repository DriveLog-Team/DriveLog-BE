package com.drivelog.media;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("media")
class TripController {
    private final MediaJobController jobs;
    TripController(MediaJobController jobs) { this.jobs=jobs; }
    @GetMapping("/api/trips")
    @Operation(operationId="listRegisteredTrips", tags="정차 후 미디어 작업", summary="팀 작업공간에 등록한 운행 ID 목록",
            description="선택한 ID를 추천 요청의 tripId로 보낸다. 차량 자동 수집 목록은 아니다.",
            security=@SecurityRequirement(name="operatorToken"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="401",description="HTTPS 및 팀 토큰 필요",content=@io.swagger.v3.oas.annotations.media.Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="200",useReturnTypeSchema=true,description="등록된 운행 ID 배열")
    List<String> trips(HttpServletRequest request) throws IOException { return jobs.availableTrips(request); }
}
