package com.drivelog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "drivelog.release=contract-test")
class DeploymentContractTest {
    @LocalServerPort
    private int port;

    @Test
    void reportsTheReleaseUsedForDeploymentVerification() throws Exception {
        var response = get("/api/status");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"release\":\"contract-test\"", "\"service\":\"drivelog-api\"");
    }

    @Test
    void exposesHealthWithoutManagementDetails() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("components");
        assertThat(get("/actuator/env").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
