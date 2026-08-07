package com.whatsappbot.project;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds the {@code app.project.*} settings so project defaults stay configuration, not literals. */
@Configuration
@EnableConfigurationProperties(ProjectProperties.class)
public class ProjectConfig {
}
