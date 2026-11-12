package com.drivelog.highlight;

/** Encoding targets, not a claim that the encoder or social publishing is deployed. */
public enum ExportProfile {
    STORY_15(15_000, 20_000, "INSTAGRAM_STORIES_COMPOSER"),
    SHORT_30(30_000, 30_000, "LOCAL_FILE_EXPORT");

    private final long targetDurationMs;
    private final long maximumDurationMs;
    private final String destination;

    ExportProfile(long targetDurationMs, long maximumDurationMs, String destination) {
        this.targetDurationMs = targetDurationMs;
        this.maximumDurationMs = maximumDurationMs;
        this.destination = destination;
    }

    public long targetDurationMs() { return targetDurationMs; }

    public record Description(String id, long targetDurationMs, long maximumDurationMs,
            int width, int height, String container, String videoCodec, String destination,
            boolean renderingAvailable, boolean automaticPublicationAvailable) {}

    public Description describe() {
        return new Description(name(), targetDurationMs, maximumDurationMs, 1080, 1920,
                "mp4", "h264", destination, false, false);
    }
}
