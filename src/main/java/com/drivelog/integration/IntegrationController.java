package com.drivelog.integration;

import com.drivelog.highlight.ExportProfile;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, non-sensitive integration status. No client may grant an OEM capability. */
@RestController
public class IntegrationController {
    public record Integration(String id, String executionLocation, boolean publicReferenceAvailable,
            boolean targetExecutionVerified, boolean operational, List<String> blockers) {}
    public record Readiness(String contractVersion, String reviewedOn, String automaticHighlightStatus,
            List<Integration> integrations) {}

    @GetMapping("/api/v1/integrations")
    @io.swagger.v3.oas.annotations.Operation(operationId="getIntegrationReadiness", tags="서버 상태",
            summary="차량·외부 연동 검증 상태 조회", description="operational 및 blockers로 운영 지원 여부를 판단한다.")
    public Readiness integrations() {
        return new Readiness("1", "2026-11-11", "BLOCKED_VEHICLE_VIDEO_SOURCE_UNVERIFIED", List.of(
                new Integration("vehicle-video", "VEHICLE", false, false, false,
                        List.of("PUBLIC_ACQUISITION_API_NOT_IDENTIFIED", "OEM_PERMISSION_UNVERIFIED", "NO_TARGET_DEVICE")),
                new Integration("pleos-vehicle-state", "VEHICLE", true, false, false,
                        List.of("TARGET_CAPABILITY_AND_PERMISSION_UNVERIFIED")),
                new Integration("pleos-navi", "VEHICLE", true, false, false,
                        List.of("TARGET_LOCATION_CONTINUITY_UNVERIFIED")),
                new Integration("pleos-fused-location", "VEHICLE", true, false, false,
                        List.of("PROVISIONING_GUIDE_SAYS_COMING_SOON", "TARGET_CAPABILITY_UNVERIFIED")),
                new Integration("instagram-stories", "MOBILE", true, false, false,
                        List.of("META_APP_ID_AND_MOBILE_CLIENT_NOT_CONFIGURED")),
                new Integration("kakaotalk-share", "MOBILE_OR_WEB", true, false, false,
                        List.of("KAKAO_APP_AND_HTTPS_SHARE_DOMAIN_NOT_CONFIGURED")),
                new Integration("media-storage-and-worker", "SERVER", true, false, false,
                        List.of("MEDIA_BUCKET_AND_WORKER_NOT_PROVISIONED", "HTTPS_AND_USER_AUTH_REQUIRED"))));
    }

    @GetMapping("/api/v1/export-profiles")
    @io.swagger.v3.oas.annotations.Operation(operationId="getExportProfiles", tags="서버 상태",summary="숏폼 출력 규격과 운영 가용성 조회")
    public List<ExportProfile.Description> exportProfiles() {
        return java.util.Arrays.stream(ExportProfile.values()).map(ExportProfile::describe).toList();
    }
}
