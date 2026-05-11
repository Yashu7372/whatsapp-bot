package com.whatsappbot.application.template;

import java.util.List;

public record SendTemplateRequest(
        String templateCode,
        String languageCode,
        String toPhoneNumber,
        String componentsJson,
        List<String> bodyTextParameters
) {
}
