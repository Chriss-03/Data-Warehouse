package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.*;

import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/ingest")
public class IngestController {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentVersionRepository instrumentVersionRepository;
    private final TimeSeriesRepository timeSeriesRepository;
    private final TimeSeriesPointsRepository timeSeriesPointsRepository;
    private final DataSourceRepository dataSourceRepository;

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
     * POST /ingest/stooq/daily?symbol=AAPL.US&sourceId=20
     * POST /ingest/stooq/daily?symbol=AAPL.US&sourceId=20&instrumentId=1001
     *
     * Safe to rerun: instrument and series creation are idempotent.
     * Each ingest always appends a new version record (temporal audit trail).
     * Buckets are upserted by (seriesId, bucketStart) — rerun overwrites stale data safely.
     */
    @PostMapping("/stooq/daily")
    public ResponseEntity<?> ingestStooqDaily(
            @RequestParam String symbol,
            @RequestParam long sourceId,
            @RequestParam(required = false) Long instrumentId
    ) {
        // 0) Ensure source exists (provenance)
        dataSourceRepository.findBySourceId(sourceId).orElseGet(() -> {
            DataSourceDoc s = new DataSourceDoc(null, sourceId, "Stooq");
            return dataSourceRepository.save(s);
        });

        long instId = (instrumentId != null) ? instrumentId : deriveInstrumentId(symbol);

        // 1) Fetch CSV from Stooq
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

        // 2) Idempotent instrument upsert
        if (!instrumentRepository.existsByInstrumentId(instId)) {
            instrumentRepository.save(new InstrumentDoc(null, instId, symbol, "UNKNOWN", "ACTIVE", 1L));
        }

        // 3) Append-only version record — always insert (temporal audit trail)
        long versionId = Instant.now().toEpochMilli() * 1000L + (instId % 1000L);
        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null,
                versionId,
                instId,
                sourceId,
                Instant.now().toString(),
                Instant.now().toString(),
                "UPSERT",
                "{\"provider\":\"stooq\",\"symbol\":\"" + escapeJson(symbol) + "\"}"
        ));

        // 4) Idempotent series upsert
        String granularity = "1d";
        TimeSeriesDoc series = timeSeriesRepository
                .findFirstByInstrumentIdAndSourceIdAndGranularity(instId, sourceId, granularity)
                .orElseGet(() -> {
                    long newSeriesId = Math.abs(Objects.hash(instId, sourceId, granularity)) % 1_000_000_000L + 1L;
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
        List<Map<String, Object>> points = parseStooqCsvToPoints(csv);

        // 6) Bucket by month
        Map<String, List<Map<String, Object>>> bucketsByStart = new LinkedHashMap<>();
        for (Map<String, Object> p : points) {
            String ts = (String) p.get("ts");
            String bucketStart = ts.substring(0, 7) + "-01T00:00:00Z";
            bucketsByStart.computeIfAbsent(bucketStart, k -> new ArrayList<>()).add(p);
        }

        // 7) Upsert buckets by (seriesId, bucketStart) — safe to rerun
        int insertedBuckets = 0;
        int updatedBuckets = 0;
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
                TimeSeriesPointsDoc doc = existing.get();
                doc.setPoints(pointsJson);
                timeSeriesPointsRepository.save(doc);
                updatedBuckets++;
            } else {
                timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                        null, series.getSeriesId(), bucketStart, pointsJson
                ));
                insertedBuckets++;
            }
        }

        return ResponseEntity.ok(Map.of(
                "message",        "Ingested from external provider (stooq)",
                "symbol",         symbol,
                "instrumentId",   instId,
                "sourceId",       sourceId,
                "seriesId",       series.getSeriesId(),
                "pointsCount",    points.size(),
                "bucketsInserted", insertedBuckets,
                "bucketsUpdated",  updatedBuckets
        ));
    }

    // ---------- Helpers — public so CsvParser utility and tests can use them ----------

    public long deriveInstrumentId(String symbol) {
        return Math.abs(symbol.hashCode()) + 100000L;
    }

    public List<Map<String, Object>> parseStooqCsvToPoints(String csv) {
        List<Map<String, Object>> out = new ArrayList<>();
        String[] lines = csv.split("\\r?\\n");
        if (lines.length <= 1) return out;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < 6) continue;

            String ts = parts[0] + "T00:00:00Z";

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("open",   parseDoubleSafe(parts[1]));
            values.put("high",   parseDoubleSafe(parts[2]));
            values.put("low",    parseDoubleSafe(parts[3]));
            values.put("close",  parseDoubleSafe(parts[4]));
            values.put("volume", parseLongSafe(parts[5]));

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("ts", ts);
            point.put("values", values);
            out.add(point);
        }
        return out;
    }

    public Double parseDoubleSafe(String s) {
        try { return Double.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    public Long parseLongSafe(String s) {
        try { return Long.valueOf(s.trim()); } catch (Exception e) { return null; }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String toJsonArrayString(List<Map<String, Object>> points) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < points.size(); i++) {
            sb.append(toJsonObject(points.get(i)));
            if (i < points.size() - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String toJsonObject(Map<String, Object> obj) {
        StringBuilder sb = new StringBuilder("{");
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
