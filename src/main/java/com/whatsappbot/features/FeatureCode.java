package com.whatsappbot.features;

public final class FeatureCode {
    public static final String WHATSAPP_BOT              = "WHATSAPP_BOT";
    public static final String CRM_DASHBOARD             = "CRM_DASHBOARD";
    public static final String CAMPAIGNS                 = "CAMPAIGNS";
    public static final String DOCUMENT_CONTROL          = "DOCUMENT_CONTROL";
    public static final String ZERO_KNOWLEDGE_STORAGE    = "ZERO_KNOWLEDGE_STORAGE";
    public static final String DOCUMENT_AI_ANALYZER      = "DOCUMENT_AI_ANALYZER";
    public static final String AI_TREND_PICKER           = "AI_TREND_PICKER";
    public static final String AI_CONTENT_GENERATOR      = "AI_CONTENT_GENERATOR";
    public static final String MEDIA_LIBRARY             = "MEDIA_LIBRARY";
    public static final String VIDEO_TEMPLATE_ENGINE     = "VIDEO_TEMPLATE_ENGINE";
    public static final String SCHEDULED_PUBLISHING      = "SCHEDULED_PUBLISHING";
    public static final String INSTAGRAM_PUBLISHING      = "INSTAGRAM_PUBLISHING";
    public static final String YOUTUBE_PUBLISHING        = "YOUTUBE_PUBLISHING";
    public static final String BYO_MEDIA_STORAGE         = "BYO_MEDIA_STORAGE";
    public static final String BYO_DOCUMENT_STORAGE      = "BYO_DOCUMENT_STORAGE";
    public static final String CUSTOMER_KMS              = "CUSTOMER_KMS";

    // Settings module — these predate this class but weren't mirrored here yet; feature_catalog
    // (V1__baseline.sql) is still the source of truth for the full catalog, this is just the
    // Java-side constant so call sites don't hardcode the string.
    public static final String SETTINGS_WEBHOOK          = "SETTINGS_WEBHOOK";
    public static final String SETTINGS_BOT              = "SETTINGS_BOT";
    public static final String SETTINGS_TEAM             = "SETTINGS_TEAM";
    public static final String SETTINGS_SOCIAL           = "SETTINGS_SOCIAL";
    public static final String SETTINGS_STORAGE          = "SETTINGS_STORAGE";
    public static final String SETTINGS_BILLING          = "SETTINGS_BILLING";

    private FeatureCode() {}
}
