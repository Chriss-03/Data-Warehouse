package com.DataWarehouseProject.DataWarehouse.service;

import com.DataWarehouseProject.DataWarehouse.model.TimeSeriesDoc;
import com.DataWarehouseProject.DataWarehouse.model.TimeSeriesPointsDoc;
import com.DataWarehouseProject.DataWarehouse.repository.TimeSeriesPointsRepository;
import com.DataWarehouseProject.DataWarehouse.repository.TimeSeriesRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AnalyticsService {

    private final TimeSeriesRepository timeSeriesRepository;
    private final TimeSeriesPointsRepository timeSeriesPointsRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnalyticsService(TimeSeriesRepository timeSeriesRepository,
                            TimeSeriesPointsRepository timeSeriesPointsRepository) {
        this.timeSeriesRepository = timeSeriesRepository;
        this.timeSeriesPointsRepository = timeSeriesPointsRepository;
    }

    public Map<String, Object> summary(long instrumentId,
                                       long sourceId,
                                       String granularity,
                                       String from,
                                       String to) {

        TimeSeriesDoc series = timeSeriesRepository
                .findFirstByInstrumentIdAndSourceIdAndGranularity(instrumentId, sourceId, granularity)
                .orElseThrow(() -> new NoSuchElementException("Series not found"));

        // Fetch all buckets for the series; filter buckets by bucketStart range
        List<TimeSeriesPointsDoc> allBuckets =
                timeSeriesPointsRepository.findBySeriesIdOrderByBucketStartAsc(series.getSeriesId());

        List<TimeSeriesPointsDoc> buckets = new ArrayList<>();
        for (TimeSeriesPointsDoc b : allBuckets) {
            String bs = b.getBucketStart();
            if (bs != null && bs.compareTo(from) >= 0 && bs.compareTo(to) <= 0) {
                buckets.add(b);
            }
        }

        // Parse points and compute stats (filter by point ts too, for correctness)
        long count = 0;
        double minClose = Double.POSITIVE_INFINITY;
        double maxClose = Double.NEGATIVE_INFINITY;
        double sumClose = 0.0;
        double firstClose = Double.NaN;
        double lastClose = Double.NaN;
        long totalVolume = 0;

        // We’ll track first/last by timestamp
        String firstTs = null;
        String lastTs = null;

        for (TimeSeriesPointsDoc bucket : buckets) {
            String pointsJson = bucket.getPoints();
            if (pointsJson == null || pointsJson.isBlank()) continue;

            List<Map<String, Object>> points = parsePoints(pointsJson);

            for (Map<String, Object> p : points) {
                Object tsObj = p.get("ts");
                if (!(tsObj instanceof String ts)) continue;

                // Filter exact point timestamps inside [from,to]
                if (ts.compareTo(from) < 0 || ts.compareTo(to) > 0) continue;

                Object valuesObj = p.get("values");
                if (!(valuesObj instanceof Map<?, ?> valuesRaw)) continue;

                // close
                Double close = toDouble(valuesRaw.get("close"));
                if (close == null) continue;

                // volume (optional)
                Long volume = toLong(valuesRaw.get("volume"));
                if (volume != null) totalVolume += volume;

                // stats
                count++;
                sumClose += close;
                if (close < minClose) minClose = close;
                if (close > maxClose) maxClose = close;

                // first/last close by ts
                if (firstTs == null || ts.compareTo(firstTs) < 0) {
                    firstTs = ts;
                    firstClose = close;
                }
                if (lastTs == null || ts.compareTo(lastTs) > 0) {
                    lastTs = ts;
                    lastClose = close;
                }
            }
        }

        Double avgClose = (count == 0) ? null : (sumClose / count);
        Double simpleReturn = (count == 0 || Double.isNaN(firstClose) || Double.isNaN(lastClose) || firstClose == 0.0)
                ? null
                : (lastClose / firstClose) - 1.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrumentId", instrumentId);
        out.put("sourceId", sourceId);
        out.put("granularity", granularity);
        out.put("from", from);
        out.put("to", to);

        out.put("seriesId", series.getSeriesId());
        out.put("indicatorSet", series.getIndicatorSet());

        out.put("pointsCount", count);
        out.put("minClose", (count == 0) ? null : minClose);
        out.put("maxClose", (count == 0) ? null : maxClose);
        out.put("avgClose", avgClose);
        out.put("totalVolume", totalVolume);

        out.put("firstTs", firstTs);
        out.put("firstClose", (firstTs == null) ? null : firstClose);
        out.put("lastTs", lastTs);
        out.put("lastClose", (lastTs == null) ? null : lastClose);
        out.put("simpleReturn", simpleReturn);

        return out;
    }

    private List<Map<String, Object>> parsePoints(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Double toDouble(Object x) {
        if (x == null) return null;
        if (x instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(x)); } catch (Exception e) { return null; }
    }

    private Long toLong(Object x) {
        if (x == null) return null;
        if (x instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(x)); } catch (Exception e) { return null; }
    }
}