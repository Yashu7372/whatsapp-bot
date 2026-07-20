package com.whatsappbot.trend;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@Order(20)
public class GoogleTrendsProvider implements TrendProvider {

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://trends.google.com")
            .build();

    @Override
    public String code() {
        return "GOOGLE_TRENDS_RSS";
    }

    @Override
    public String displayName() {
        return "Google Trends RSS";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean supports(String platformCode) {
        return true;
    }

    @Override
    public List<ObservedTrend> discover(TrendQuery query) {
        String geo = normalizeCountry(query.country());
        try {
            String xml = restClient.get()
                    .uri(uri -> uri.path("/trending/rss").queryParam("geo", geo).build())
                    .retrieve()
                    .body(String.class);
            if (xml == null || xml.isBlank()) {
                return List.of();
            }
            return parse(xml, query);
        } catch (Exception e) {
            log.warn("Google Trends discovery failed. geo={} error={}", geo, e.getMessage());
            return List.of();
        }
    }

    private List<ObservedTrend> parse(String xml, TrendQuery query) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList items = document.getElementsByTagName("item");
        List<ObservedTrend> results = new ArrayList<>();
        int limit = Math.min(query.count(), items.getLength());
        for (int i = 0; i < limit; i++) {
            Element item = (Element) items.item(i);
            String title = text(item, "title");
            if (title == null || title.isBlank()) {
                continue;
            }
            String traffic = text(item, "ht:approx_traffic");
            double score = Math.max(0.55, 1.0 - (i * 0.06));
            String topic = "%s is currently appearing in Google trending searches%s. Adapt the angle for %s content in %s."
                    .formatted(title, traffic == null ? "" : " with " + traffic + " search interest",
                            query.platformCode(), query.industry());
            results.add(new ObservedTrend(
                    title,
                    "#" + title.replaceAll("[^A-Za-z0-9]", ""),
                    topic,
                    score,
                    displayName()
            ));
        }
        return results;
    }

    private String text(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private String normalizeCountry(String country) {
        if (country == null || country.isBlank() || "GLOBAL".equalsIgnoreCase(country)) {
            return "US";
        }
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UAE", "UNITED ARAB EMIRATES", "DUBAI" -> "AE";
            case "INDIA" -> "IN";
            case "UNITED KINGDOM", "UK" -> "GB";
            case "UNITED STATES", "USA" -> "US";
            default -> normalized.length() == 2 ? normalized : "US";
        };
    }
}
