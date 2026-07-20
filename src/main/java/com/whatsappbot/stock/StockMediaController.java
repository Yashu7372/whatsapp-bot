package com.whatsappbot.stock;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-media")
@RequiredArgsConstructor
public class StockMediaController {

    private final StockMediaService stockMediaService;

    @GetMapping("/capabilities")
    public ResponseEntity<StockMediaService.Capabilities> capabilities() {
        return ResponseEntity.ok(stockMediaService.capabilities());
    }

    @GetMapping("/search")
    public ResponseEntity<StockMediaService.SearchResult> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "AUTO") String provider,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int perPage) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        return ResponseEntity.ok(stockMediaService.search(query.trim(), provider, page, perPage));
    }
}
