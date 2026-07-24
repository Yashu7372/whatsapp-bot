package com.whatsappbot.video;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "app.video.blender")
public record BlenderRenderProperties(
        String executable,
        Path pythonScript,
        Path templateDir,
        Path workDir,
        int timeoutSeconds
) {
}
