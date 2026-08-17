package com.whatsappbot.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final FeatureAuthorizationInterceptor featureAuthorizationInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(featureAuthorizationInterceptor)
                .addPathPatterns("/api/v1/**", "/api/tenants/**")
                .excludePathPatterns("/api/v1/auth/**", "/api/v1/me/**", "/api/v1/public/**");
    }
}
