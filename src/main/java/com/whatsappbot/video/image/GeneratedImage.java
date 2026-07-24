package com.whatsappbot.video.image;

import java.math.BigDecimal;

public record GeneratedImage(
        byte[] data,
        String mimeType,
        String provider,
        BigDecimal actualCostUsd
) {
}
