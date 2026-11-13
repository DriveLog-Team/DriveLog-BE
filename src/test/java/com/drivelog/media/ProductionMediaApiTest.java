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
    HttpResponse<String> sendJson(String method,String path,String token,String json) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("X-Forwarded-Proto","https").header("Authorization","Bearer "+token)
                .header("Content-Type","application/json")
                .method(method,HttpRequest.BodyPublishers.ofString(json)).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
    @Test void voiceCommandAndPassengerTapAppearInOneTimeline() throws Exception {
        var mapper=JsonMapper.builder().build();
        var a=mapper.readTree(sendJson("POST","/api/accounts",KEY,"").body());
        var b=mapper.readTree(sendJson("POST","/api/accounts",KEY,"").body());
        var source=java.util.UUID.randomUUID();
        String registration="""
                {"sourceId":"%s","startedAt":"2026-11-13T05:00:00Z","owner":"%s","occupants":[
                {"occupant":"%s","under14":false},{"occupant":"%s","under14":false}]}
                """.formatted(source,a.get("accountId").asText(),a.get("accountId").asText(),b.get("accountId").asText());
        assertThat(sendJson("POST","/api/private-trips/departures",KEY,registration).statusCode()).isEqualTo(200);
        String trip="/api/private-trips/trip-"+source;
        String voice=trip+"/voice-marks/"+java.util.UUID.randomUUID();
        String token=a.get("accessToken").asText(),passenger=b.get("accessToken").asText();
        assertThat(sendJson("PUT",voice,token,"{\"atMs\":3200,\"command\":\"집까지 길 알려줘\"}").statusCode()).isEqualTo(422);
        assertThat(sendJson("PUT",voice,token,"{\"atMs\":3200,\"command\":\"방금 거 저장해줘. 다 같이 웃는 데까지.\"}").body()).contains("VOICE_UNTIL_LAUGHTER");
        String tap=trip+"/marks/"+java.util.UUID.randomUUID();
        for(int i=0;i<2;i++) assertThat(sendJson("PUT",tap,passenger,"{\"kind\":\"TAP\",\"atMs\":1000}").statusCode()).isEqualTo(200);
        var entries=mapper.readTree(sendJson("GET",trip+"/timeline",token,"").body()).get("entries");
        assertThat(entries.size()).isEqualTo(2);
        assertThat(entries.get(0).get("mark").get("author").asText()).isEqualTo(b.get("accountId").asText());
    }
    @Test void guideAndPublishedContractCoverTheProtectedFlow() throws Exception {
        assertThat(get("/api/frontend.html",false,false).body()).contains("/api/media/jobs/{id}/edits");
        var doc=JsonMapper.builder().build().readTree(get("/api/v3/api-docs",false,false).body());
        assertThat(doc.get("paths").propertyNames()).contains("/api/private-trips/{id}/finalize","/api/library/{id}/clip","/api/library/{tripId}/handoffs").noneMatch(p->p.contains("/v1/"));
        var paths=doc.get("paths");
        for(var path:paths.propertyNames()) {
            if(!path.startsWith("/api/private-trips") && !path.startsWith("/api/library") && !path.equals("/api/accounts")) continue;
            for(var operation:paths.get(path)) {
                var responses=operation.get("responses");
                assertThat(responses.propertyNames()).as(path).anyMatch(code -> code.startsWith("2") && responses.get(code).has("content"));
            }
        }
        assertThat(paths.get("/api/library").get("get").get("responses").get("200").toString()).contains("array","Item");
        assertThat(get("/api/trips",true,false).statusCode()).isEqualTo(401);
        assertThat(get("/api/trips",false,true).statusCode()).isEqualTo(401);
        assertThat(get("/api/trips",true,true).body()).isEqualTo("[]");
        assertThat(get("/api/handoffs/open",true,false).body()).contains("/api/handoffs/redeem");
    }
}
