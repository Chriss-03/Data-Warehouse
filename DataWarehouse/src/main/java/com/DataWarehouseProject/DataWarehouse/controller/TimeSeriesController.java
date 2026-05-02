package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.model.TimeSeriesDoc;
import com.DataWarehouseProject.DataWarehouse.model.TimeSeriesPointsDoc;
import com.DataWarehouseProject.DataWarehouse.repository.TimeSeriesPointsRepository;
import com.DataWarehouseProject.DataWarehouse.repository.TimeSeriesRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/timeseries")
public class TimeSeriesController {

    private final TimeSeriesRepository timeSeriesRepository;
    private final TimeSeriesPointsRepository timeSeriesPointsRepository;

    public TimeSeriesController(TimeSeriesRepository timeSeriesRepository,
                                TimeSeriesPointsRepository timeSeriesPointsRepository) {
        this.timeSeriesRepository = timeSeriesRepository;
        this.timeSeriesPointsRepository = timeSeriesPointsRepository;
    }

    // Q5: time-series for asset + source (+ granularity) in range
    // Example: /timeseries?instrumentId=1001&sourceId=10&granularity=1d&from=2026-03-01T00:00:00Z&to=2026-03-31T00:00:00Z
    @GetMapping
    public ResponseEntity<?> getTimeSeries(
            @RequestParam long instrumentId,
            @RequestParam long sourceId,
            @RequestParam(defaultValue = "1d") String granularity,
            @RequestParam String from,
            @RequestParam String to
    ) {
        Optional<TimeSeriesDoc> seriesOpt =
                timeSeriesRepository.findFirstByInstrumentIdAndSourceIdAndGranularity(instrumentId, sourceId, granularity);

        if (seriesOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TimeSeriesDoc series = seriesOpt.get();

//        List<TimeSeriesPointsDoc> buckets =
//                timeSeriesPointsRepository.findBySeriesIdAndBucketStartBetweenOrderByBucketStartAsc(
//                        series.getSeriesId(), from, to
//                );

        List<TimeSeriesPointsDoc> allBuckets =
                timeSeriesPointsRepository.findBySeriesIdOrderByBucketStartAsc(series.getSeriesId());

        List<TimeSeriesPointsDoc> buckets = new ArrayList<>();
        for (TimeSeriesPointsDoc b : allBuckets) {
            String bs = b.getBucketStart();
            if (bs != null && bs.compareTo(from) >= 0 && bs.compareTo(to) <= 0) {
                buckets.add(b);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("series", Map.of(
                "seriesId", series.getSeriesId(),
                "instrumentId", series.getInstrumentId(),
                "sourceId", series.getSourceId(),
                "granularity", series.getGranularity(),
                "indicatorSet", series.getIndicatorSet()
        ));

        // return bucketStart + points JSON string
        List<Map<String, Object>> bucketOut = new ArrayList<>();
        for (TimeSeriesPointsDoc b : buckets) {
            bucketOut.add(Map.of(
                    "bucketStart", b.getBucketStart(),
                    "points", b.getPoints()
            ));
        }
        out.put("buckets", bucketOut);

        return ResponseEntity.ok(out);
    }
}