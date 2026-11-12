package com.drivelog.media;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("media")
class ProductionMediaApiTest {
    @TempDir static Path root;
    @LocalServerPort int port;
    static final String KEY="test-only-operator-key-not-a-production-token";
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("media.trips", () -> root.toString());
        r.add("media.library", () -> root.resolve("renders").toString());
        r.add("media.worker", () -> Path.of("media/worker.py").toAbsolutePath().toString());
        r.add("media.token", () -> KEY);
        r.add("media.handoff-origin", () -> "https://example.invalid");
    }
    HttpResponse<String> get(String path,boolean tls,boolean auth) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if (tls) req.header("X-Forwarded-Proto","https"); // TLS terminated by trusted nginx
        if (auth) req.header("Authorization","Bearer "+KEY);
        return HttpClient.newHttpClient().send(req.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void guideAndPublishedContractCoverTheProtectedFlow() throws Exception {
        assertThat(get("/api/frontend.html",false,false).body()).contains("/api/media/jobs/{id}/edits");
        var doc=JsonMapper.builder().build().readTree(get("/api/v3/api-docs",false,false).body());
        assertThat(doc.get("paths").propertyNames()).hasSize(13).noneMatch(p->p.contains("/v1/"));
        assertThat(get("/api/trips",true,false).statusCode()).isEqualTo(401);
        assertThat(get("/api/trips",false,true).statusCode()).isEqualTo(401);
        assertThat(get("/api/trips",true,true).body()).isEqualTo("[]");
        assertThat(get("/api/handoffs/open",true,false).body()).contains("/api/handoffs/redeem");
    }
}
