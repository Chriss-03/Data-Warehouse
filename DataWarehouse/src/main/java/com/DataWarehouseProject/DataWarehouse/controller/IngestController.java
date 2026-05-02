package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/ingest")
public class IngestController {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentVersionRepository instrumentVersionRepository;
    private final TimeSeriesRepository timeSeriesRepository;
    private final TimeSeriesPointsRepository timeSeriesPointsRepository;
    private final DataSourceRepository dataSourceRepository;

    // id generator
    private final AtomicLong seriesIdGen = new AtomicLong(7001L);
    private final AtomicLong versionIdGen = new AtomicLong(9001L);

    private final RestClient restClient = RestClient.create();

    public IngestController(
            InstrumentRepository instrumentRepository,
            InstrumentVersionRepository instrumentVersionRepository,
            TimeSeriesRepository timeSeriesRepository,
            TimeSeriesPointsRepository timeSeriesPointsRepository,
            DataSourceRepository dataSourceRepository
    ) {
        this.instrumentRepository = instrumentRepository;
        this.instrumentVersionRepository = instrumentVersionRepository;
        this.timeSeriesRepository = timeSeriesRepository;
        this.timeSeriesPointsRepository = timeSeriesPointsRepository;
        this.dataSourceRepository = dataSourceRepository;
    }

    /**
     * External ingest from Stooq daily CSV.
     *
     * Example:
     * POST /ingest/stooq/daily?symbol=AAPL.US&sourceId=10&instrumentId=1001
     *
     * If instrumentId is omitted, we derive one from symbol hash (stable but not perfect).
     */
    @PostMapping("/stooq/daily")
    public ResponseEntity<?> ingestStooqDaily(
            @RequestParam String symbol,
            @RequestParam long sourceId,
            @RequestParam(required = false) Long instrumentId
    ) {
        // 0) Ensure source exists (provenance)
        dataSourceRepository.findBySourceId(sourceId).orElseGet(() -> {
            // auto-create a data source placeholder if missing
            DataSourceDoc s = new DataSourceDoc(null, sourceId, "Stooq");
            return dataSourceRepository.save(s);
        });

        long instId = (instrumentId != null) ? instrumentId : deriveInstrumentId(symbol);

        // 1) Fetch CSV from external provider (HTTP)
        // Stooq daily endpoint: https://stooq.com/q/d/l/?s=AAPL.US&i=d
        String url = "https://stooq.com/q/d/l/?s=" + symbol + "&i=d";

        String csv;
        try {
            csv = restClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Failed to fetch from external provider",
                    "provider", "stooq",
                    "url", url,
                    "details", e.getMessage()
            ));
        }

        if (csv == null || csv.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Empty CSV response from provider",
                    "url", url
            ));
        }

        // 2) Ensure instrument exists
        if (!instrumentRepository.existsByInstrumentId(instId)) {
            instrumentRepository.save(new InstrumentDoc(null, instId, symbol, "UNKNOWN", "ACTIVE", 1L));
        }

        // 3) Append-only metadata version (UPSERT)
        long versionId = versionIdGen.incrementAndGet();
        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null,
                versionId,
                instId,
                sourceId,
                Instant.now().toString(),    // validFrom = now
                Instant.now().toString(),    // ingestedAt = now
                "UPSERT",
                "{\"provider\":\"stooq\",\"symbol\":\"" + escapeJson(symbol) + "\"}"
        ));

        // 4) Create or get time_series definition (instrumentId + sourceId + granularity)
        String granularity = "1d";
        TimeSeriesDoc series = timeSeriesRepository
                .findFirstByInstrumentIdAndSourceIdAndGranularity(instId, sourceId, granularity)
                .orElseGet(() -> {
                    long newSeriesId = seriesIdGen.incrementAndGet();
                    return timeSeriesRepository.save(new TimeSeriesDoc(
                            null,
                            newSeriesId,
                            instId,
                            sourceId,
                            granularity,
                            "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
                    ));
                });

        // 5) Parse CSV rows into points
        // CSV format: Date,Open,High,Low,Close,Volume
        List<Map<String, Object>> points = parseStooqCsvToPoints(csv);

        // 6) Bucket by month
        // Create one document per YYYY-MM-01T00:00:00Z bucketStart
        Map<String, List<Map<String, Object>>> bucketsByStart = new LinkedHashMap<>();
        for (Map<String, Object> p : points) {
            String ts = (String) p.get("ts"); // e.g., 2026-03-01T00:00:00Z
            String bucketStart = ts.substring(0, 7) + "-01T00:00:00Z"; // YYYY-MM-01T00:00:00Z
            bucketsByStart.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(p);
        }

        // 7) Upsert buckets: (seriesId, bucketStart)
        int insertedBuckets = 0;
        for (var entry : bucketsByStart.entrySet()) {
            String bucketStart = entry.getKey();
            String pointsJson = toJsonArrayString(entry.getValue());

            Optional<TimeSeriesPointsDoc> existing = timeSeriesPointsRepository
                    .findBySeriesIdAndBucketStartBetweenOrderByBucketStartAsc(
                            series.getSeriesId(), bucketStart, bucketStart
                    )
                    .stream()
                    .findFirst();

            if (existing.isPresent()) {
                // overwrite bucket
                TimeSeriesPointsDoc doc = existing.get();
                doc.setPoints(pointsJson);
                timeSeriesPointsRepository.save(doc);
            } else {
                timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                        null,
                        series.getSeriesId(),
                        bucketStart,
                        pointsJson
                ));
                insertedBuckets++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "message", "Ingested from external provider (stooq)",
                "symbol", symbol,
                "instrumentId", instId,
                "sourceId", sourceId,
                "seriesId", series.getSeriesId(),
                "pointsCount", points.size(),
                "bucketsInserted", insertedBuckets
        ));
    }

    // ---------- Helpers ----------

    private long deriveInstrumentId(String symbol) {
        // stable numeric id
        return Math.abs(symbol.hashCode()) + 100000L;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<Map<String, Object>> parseStooqCsvToPoints(String csv) {
        List<Map<String, Object>> out = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        if (lines.length <= 1) return out;

        // skip header
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String date = parts[0]; // YYYY-MM-DD
            String open = parts[1];
            String high = parts[2];
            String low = parts[3];
            String close = parts[4];
            String volume = parts[5];

            // Convert date to ISO Z at midnight (keeps string-based time format consistent)
            String ts = date + "T00:00:00Z";

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("open", parseDoubleSafe(open));
            values.put("high", parseDoubleSafe(high));
            values.put("low", parseDoubleSafe(low));
            values.put("close", parseDoubleSafe(close));
            values.put("volume", parseLongSafe(volume));

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("ts", ts);
            point.put("values", values);

            out.add(point);
        }
        return out;
    }

    private Double parseDoubleSafe(String s) {
        try { return Double.valueOf(s); } catch (Exception e) { return null; }
    }

    private Long parseLongSafe(String s) {
        try { return Long.valueOf(s); } catch (Exception e) { return null; }
    }

    // Turn a list of {ts, values} maps into a JSON array string
    private String toJsonArrayString(List<Map<String, Object>> points) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < points.size(); i++) {
            sb.append(toJsonObject(points.get(i)));
            if (i < points.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonObject(Map<String, Object> obj) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int j = 0;
        for (var e : obj.entrySet()) {
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append(toJsonValue(e.getValue()));
            if (j < obj.size() - 1) sb.append(",");
            j++;
        }
        sb.append("}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonValue(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + escapeJson((String) v) + "\"";
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        if (v instanceof Map) return toJsonObject((Map<String, Object>) v);
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            List<Object> list = (List<Object>) v;
            for (int i = 0; i < list.size(); i++) {
                sb.append(toJsonValue(list.get(i)));
                if (i < list.size() - 1) sb.append(",");
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(String.valueOf(v)) + "\"";
    }
}