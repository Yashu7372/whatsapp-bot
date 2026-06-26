package com.whatsappbot.jobs;

public final class BackgroundJobType {
    public static final String DOCUMENT_AI_ANALYSIS = "DOCUMENT_AI_ANALYSIS";
    public static final String AI_TREND_REFRESH      = "AI_TREND_REFRESH";
    public static final String AI_SCRIPT_GENERATION  = "AI_SCRIPT_GENERATION";
    public static final String VIDEO_RENDER          = "VIDEO_RENDER";
    public static final String SOCIAL_PUBLISH        = "SOCIAL_PUBLISH";
    public static final String TOKEN_REFRESH         = "TOKEN_REFRESH";
    public static final String MEDIA_CLEANUP         = "MEDIA_CLEANUP";
    public static final String USAGE_AGGREGATION     = "USAGE_AGGREGATION";

    private BackgroundJobType() {}
}
