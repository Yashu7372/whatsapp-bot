package com.whatsappbot.trend.provider;

import com.whatsappbot.trend.TrendSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class GoogleTrendsRssProvider implements TrendProvider {

    private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
            Map.entry("UAE", "AE"),
            Map.entry("UNITED ARAB EMIRATES", "AE"),
            Map.entry("DUBAI", "AE"),
            Map.entry("INDIA", "IN"),
            Map.entry("UNITED STATES", "US"),
            Map.entry("USA", "US"),
            Map.entry("UNITED KINGDOM", "GB"),
            Map.entry("UK", "GB"),
            Map.entry("SAUDI ARABIA", "SA"),
            Map.entry("SINGAPORE", "SG"),
            Map.entry("AUSTRALIA", "AU"),
            Map.entry("CANADA", "CA")
    );

    private final WebClient webClient;
    private final TrendProviderProperties properties;

    public GoogleTrendsRssProvider(WebClient.Builder builder, TrendProviderProperties properties) {
        this.webClient = builder.build();
        this.properties = properties;
    }

    @Override
    public String code() {
        return "GOOGLE_TRENDS_RSS";
    }

    @Override
    public boolean available() {
        return properties.isGoogleRssEnabled();
    }

    @Override
    public List<TrendCandidate> discover(TrendQuery query) {
        if (!available()) {
            return List.of();
        }
        try {
            byte[] rss = webClient.get()
                    .uri(builder -> builder
                            .scheme("https")
                            .host("trends.google.com")
                            .path("/trending/rss")
                            .queryParam("geo", countryCode(query.country()))
                            .build())
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(20));
            if (rss == null || rss.length == 0) {
                return List.of();
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(rss));
            NodeList items = document.getElementsByTagName("item");

            List<TrendCandidate> result = new ArrayList<>();
            int count = Math.min(query.count(), items.getLength());
            for (int index = 0; index < count; index++) {
                Element item = (Element) items.item(index);
                String title = childText(item, "title");
                if (title == null || title.isBlank()) {
                    continue;
                }
                String traffic = childText(item, "ht:approx_traffic");
                double score = scoreTraffic(traffic, index, count);
                result.add(new TrendCandidate(
                        title,
                        "#" + title.replaceAll("[^A-Za-z0-9]", ""),
                        "Google search interest is rising" +
                                (traffic == null || traffic.isBlank() ? "." : " with approximately " + traffic + " searches."),
                        score,
                        "Google Trends RSS",
                        TrendSourceType.RSS
                ));
            }
            return result;
        } catch (Exception error) {
            log.warn("Google Trends RSS discovery failed: {}", error.getMessage());
            return List.of();
        }
    }

    private String childText(Element parent, String tagName) {
        NodeList values = parent.getElementsByTagName(tagName);
        return values.getLength() == 0 ? null : values.item(0).getTextContent().trim();
    }

    private double scoreTraffic(String traffic, int index, int count) {
        if (traffic != null && !traffic.isBlank()) {
            String normalized = traffic.toUpperCase(Locale.ROOT).replace(",", "").replace("+", "").trim();
            double multiplier = 1.0;
            if (normalized.endsWith("K")) {
                multiplier = 1_000.0;
                normalized = normalized.substring(0, normalized.length() - 1);
            } else if (normalized.endsWith("M")) {
                multiplier = 1_000_000.0;
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            try {
                double value = Double.parseDouble(normalized) * multiplier;
                return Math.min(1.0, 0.45 + Math.log10(Math.max(value, 10)) / 12.0);
            } catch (NumberFormatException ignored) {
                // Fall through to rank scoring.
            }
        }
        return Math.max(0.55, 0.95 - (index / (double) Math.max(count, 1)) * 0.35);
    }

    private String countryCode(String country) {
        if (country == null || country.isBlank() || country.equalsIgnoreCase("global")) {
            return "AE";
        }
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() == 2) {
            return normalized;
        }
        return COUNTRY_CODES.getOrDefault(normalized, "AE");
    }
}
