package com.whatsappbot.video.image;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;

@Component
public class LocalImageGenerationProvider implements ImageGenerationProvider {

    private final MediaGenerationProperties properties;
    private final WebClient webClient;

    public LocalImageGenerationProvider(MediaGenerationProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getLocal().getBaseUrl())
                .build();
    }

    @Override
    public String code() {
        return "LOCAL";
    }

    @Override
    public boolean available() {
        return properties.getLocal().isEnabled();
    }

    @Override
    public BigDecimal estimateCost(StoryboardQualityMode qualityMode) {
        return BigDecimal.ZERO;
    }

    @Override
    public GeneratedImage generate(ImageGenerationRequest request) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("prompt", request.prompt());
        body.add("qualityMode", request.qualityMode().name());
        int index = 0;
        for (ImageReference reference : request.references()) {
            String extension = reference.mimeType().contains("png") ? ".png" : ".jpg";
            body.add("references", new NamedByteArrayResource(
                    reference.data(), "reference-" + (++index) + extension));
        }

        ResponseEntity<byte[]> response = webClient.post()
                .uri("/generate")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(body))
                .retrieve()
                .toEntity(byte[].class)
                .block(properties.getLocal().getTimeout());
        byte[] image = response == null ? null : response.getBody();
        if (image == null || image.length == 0) {
            throw new IllegalStateException("Local image worker returned no image");
        }
        String mimeType = response.getHeaders().getContentType() == null
                ? "image/jpeg"
                : response.getHeaders().getContentType().toString();
        return new GeneratedImage(image, mimeType, code(), BigDecimal.ZERO);
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] data, String filename) {
            super(data);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }
}
