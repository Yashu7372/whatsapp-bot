package com.whatsappbot.platform.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PlatformPluginRegistry {

    private final Map<String, MarketingPlatformPlugin> plugins;

    public PlatformPluginRegistry(List<MarketingPlatformPlugin> pluginList) {
        this.plugins = pluginList.stream()
                .collect(Collectors.toMap(MarketingPlatformPlugin::platformCode, Function.identity()));
        log.info("Platform plugin registry initialised with {} plugin(s): {}", plugins.size(), plugins.keySet());
    }

    public MarketingPlatformPlugin getRequired(String platformCode) {
        MarketingPlatformPlugin plugin = plugins.get(platformCode);
        if (plugin == null) {
            throw new IllegalArgumentException("No plugin registered for platform: " + platformCode);
        }
        return plugin;
    }

    public Optional<MarketingPlatformPlugin> find(String platformCode) {
        return Optional.ofNullable(plugins.get(platformCode));
    }

    public Set<String> registeredCodes() {
        return plugins.keySet();
    }
}
