package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.service.AnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    // UC3: aggregation over time-series range
    // Example:
    // GET /analytics/summary?instrumentId=504815344&sourceId=10&granularity=1d&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z
    @GetMapping("/summary")
    public ResponseEntity<?> summary(
            @RequestParam long instrumentId,
            @RequestParam long sourceId,
            @RequestParam(defaultValue = "1d") String granularity,
            @RequestParam String from,
            @RequestParam String to
    ) {
        try {
            Map<String, Object> out = analyticsService.summary(instrumentId, sourceId, granularity, from, to);
            return ResponseEntity.ok(out);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }
}