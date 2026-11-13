package com.drivelog.media;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Probe only local MOV-family containers and require actual decoded stream frames. */
final class VideoProbe {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static void validate(Path file, boolean requireAudio) throws IOException {
        Path report = Files.createTempFile("track-probe-", ".json");
        try {
            Process probe = new ProcessBuilder(System.getProperty("media.ffprobe", "ffprobe"),
                    "-v", "error", "-protocol_whitelist", "file", "-f", "mov", "-threads", "1",
                    "-max_alloc", "67108864", "-read_intervals", "%+2", "-count_frames",
                    "-show_entries", "format=duration:stream=codec_type,width,height,nb_read_frames:stream_disposition=attached_pic",
                    "-of", "json", file.toString()).redirectOutput(report.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!probe.waitFor(20, TimeUnit.SECONDS) || probe.exitValue() != 0 || Files.size(report) > 65536) throw invalid();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted media probe", e);
            } finally {
                if (probe.isAlive()) probe.destroyForcibly();
            }
            var info = JSON.readTree(report.toFile());
            double seconds = info.path("format").path("duration").asDouble(0);
            boolean video = false, audio = false;
            for (var stream : info.path("streams")) {
                int width = stream.path("width").asInt(), height = stream.path("height").asInt();
                if (stream.path("nb_read_frames").asInt(0) < 1) continue;
                if ("video".equals(stream.path("codec_type").asText()) && stream.path("disposition").path("attached_pic").asInt() == 0)
                    video |= width > 0 && height > 0 && Math.max(width,height) <= 4096 && (long)width*height <= 4096L*2160;
                audio |= "audio".equals(stream.path("codec_type").asText());
            }
            if (!(seconds > 0 && seconds <= 86400) || !video || (requireAudio && !audio)) throw invalid();
        } finally {
            Files.deleteIfExists(report);
        }
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Unsupported video or missing required stream");
    }
}
