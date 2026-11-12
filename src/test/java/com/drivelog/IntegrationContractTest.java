package com.drivelog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IntegrationContractTest {
    @LocalServerPort int port;

    @Test
    void swaggerUsesOnlyRoutesForwardedByTheProductionProxy() throws Exception {
        var entry = send("GET", "/api/swagger-ui.html");
        assertThat(entry.statusCode()).isBetween(300, 399);
        String ui = URI.create(entry.headers().firstValue("Location").orElseThrow()).getPath();
        assertThat(ui).startsWith("/api/");
        assertThat(send("GET", ui).body()).contains("swagger-ui-bundle.js");
        var initializer = send("GET", ui.replace("index.html", "swagger-initializer.js"));
        assertThat(initializer.body()).contains("/api/v3/api-docs/swagger-config");
        assertThat(send("GET", "/api/v3/api-docs/swagger-config").body()).contains("\"url\":\"/api/v3/api-docs\"");
        var spec = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(send("GET", "/api/v3/api-docs").body());
        assertThat(spec.get("paths").propertyNames()).containsExactlyInAnyOrder(
                "/api/status", "/api/integrations", "/api/export-profiles");
        for (var path : spec.get("paths").propertyNames()) {
            assertThat(send("GET", path).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void reportsUnverifiedVehicleInputWithoutClaimingWorkingIntegrations() throws Exception {
        var response = send("GET", "/api/integrations");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("BLOCKED_VEHICLE_VIDEO_SOURCE_UNVERIFIED", "NO_TARGET_DEVICE")
                .doesNotContain("\"operational\":true", "\"targetExecutionVerified\":true");
    }

    @Test
    void exposesSeparateStoryAndShortTargetsWithoutClaimingPublication() throws Exception {
        var response = send("GET", "/api/export-profiles");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("STORY_15", "SHORT_30", "\"maximumDurationMs\":20000")
                .doesNotContain("\"renderingAvailable\":true", "\"automaticPublicationAvailable\":true");
    }

    @Test
    void cannotGrantVehiclePermissionThroughThePublicStatusEndpoint() throws Exception {
        assertThat(send("POST", "/api/integrations").statusCode()).isEqualTo(405);
        assertThat(send("GET", "/api/trips").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
