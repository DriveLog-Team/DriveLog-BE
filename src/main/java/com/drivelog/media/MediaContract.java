package com.drivelog.media;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Wire format of the worker's JSON result, also used by the generated OpenAPI document. */
public final class MediaContract {
    private MediaContract() {}

    @Schema(requiredProperties={"jobId"})
    public record Accepted(UUID jobId) {}
    @Schema(requiredProperties={"jobId","state"})
    public record Status(UUID jobId,
            @Schema(allowableValues={"QUEUED","RUNNING","READY","FAILED"}) String state,
            @Schema(description="READY일 때만 포함") Result result) {}
    @Schema(requiredProperties={"tripId","profile","version","sourceRevision","status","cards"})
    public record Result(String tripId, String profile, String version, String sourceRevision,
            @Schema(allowableValues={"READY","NO_SPEECH_CANDIDATE","NO_MEMORY_CANDIDATE"}) String status,
            List<Card> cards) {}
    @Schema(requiredProperties={"id","group","startMs","endMs","title","summary","cues","file"})
    public record Card(String id, int group, long startMs, long endMs, String title, String summary,
            @Schema(description="로컬 의미 모델의 유사도 차이") Double score,
            @Schema(description="Gemini 추천 순번, 1부터 시작") Integer rank,
            List<Cue> cues, Recommendation recommendation, String file) {}
    @Schema(requiredProperties={"startMs","endMs","text"})
    public record Cue(@Schema(description="운행 시작 기준 밀리초") long startMs, long endMs, String text) {}
    @Schema(requiredProperties={"category","method","basis","quote","quoteStartMs","quoteEndMs"})
    public record Recommendation(String category, String method, String basis, String quote,
            long quoteStartMs, long quoteEndMs, String provider, String role, String reasonCode, String reason) {}
    @Schema(requiredProperties={"functionName","status","message","mode","data"})
    public record Callback(String functionName,
            @Schema(allowableValues={"success","fail"}) String status, String message, String mode,
            @Schema(description="추천 발췌 목록을 직렬화한 JSON 문자열. JSON.parse로 배열을 읽는다.") String data) {}
}
