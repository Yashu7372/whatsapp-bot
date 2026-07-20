package com.whatsappbot.stockmedia;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock-media")
@RequiredArgsConstructor
public class StockMediaController {

    private final StockMediaService stockMediaService;

    @GetMapping("/search")
    public StockMediaService.SearchResult search(
            @RequestParam String query,
            @RequestParam(defaultValue = "AUTO") String provider,
            @RequestParam(defaultValue = "12") int limit
    ) {
        return stockMediaService.search(query, provider, limit);
    }
}
