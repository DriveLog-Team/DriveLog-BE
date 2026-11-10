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
    public Map<String, String> status() {
        return Map.of("service", "drivelog-api", "status", "ready", "release", release);
    }
}
