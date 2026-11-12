package com.drivelog;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final String release;

    public StatusController(@Value("${drivelog.release:local}") String release) {
        this.release = release;
    }

    @GetMapping("/api/status")
    @io.swagger.v3.oas.annotations.Operation(operationId="getDeploymentStatus", tags="서버 상태",summary="현재 서비스와 배포 버전 확인")
    public Map<String, String> status() {
        return Map.of("service", "drivelog-api", "status", "ready", "release", release);
    }
}
