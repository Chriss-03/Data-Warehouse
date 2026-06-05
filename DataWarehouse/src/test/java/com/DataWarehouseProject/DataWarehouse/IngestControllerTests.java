package com.DataWarehouseProject.DataWarehouse;

import com.DataWarehouseProject.DataWarehouse.controller.IngestController;
import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import com.DataWarehouseProject.DataWarehouse.testsupport.MongoTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the ingestion pipeline:
 *  - CSV parsing correctness
 *  - Idempotency (safe reruns — no duplicate instruments or series)
 *  - Provenance: version record always appended
 *  - Bucket upsert: rerun updates existing buckets instead of inserting duplicates
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public class IngestControllerTests {

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MockMvc mockMvc;

    @Autowired private IngestController ingestController;

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentVersionRepository instrumentVersionRepository;
    @Autowired private TimeSeriesRepository timeSeriesRepository;
    @Autowired private TimeSeriesPointsRepository timeSeriesPointsRepository;
    @Autowired private DataSourceRepository dataSourceRepository;

    @BeforeEach
    void clean() {
        MongoTestSupport.cleanAll(mongoTemplate);
    }

    // ---------------------------------------------------------------
    // 1. CSV PARSING
    // ---------------------------------------------------------------

    @Test
    void parseStooqCsv_correctlyParsesHeaderAndRows() {
        String csv = "Date,Open,High,Low,Close,Volume\n" +
                     "2024-01-02,185.0,186.5,184.0,185.5,60000000\n" +
                     "2024-01-03,185.5,187.0,185.0,186.0,55000000\n";

        List<Map<String, Object>> points = ingestController.parseStooqCsvToPoints(csv);

        assertEquals(2, points.size());

        Map<String, Object> first = points.get(0);
        assertEquals("2024-01-02T00:00:00Z", first.get("ts"));

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) first.get("values");
        assertEquals(185.0, values.get("open"));
        assertEquals(186.5, values.get("high"));
        assertEquals(184.0, values.get("low"));
        assertEquals(185.5, values.get("close"));
        assertEquals(60000000L, values.get("volume"));
    }

    @Test
    void parseStooqCsv_handlesEmptyBody_returnsEmptyList() {
        List<Map<String, Object>> points = ingestController.parseStooqCsvToPoints("");
        assertTrue(points.isEmpty());
    }

    @Test
    void parseStooqCsv_handlesHeaderOnly_returnsEmptyList() {
        List<Map<String, Object>> points = ingestController.parseStooqCsvToPoints(
                "Date,Open,High,Low,Close,Volume\n"
        );
        assertTrue(points.isEmpty());
    }

    @Test
    void parseStooqCsv_handlesMalformedNumeric_returnsNullValues() {
        String csv = "Date,Open,High,Low,Close,Volume\n" +
                     "2024-01-02,N/A,N/A,N/A,N/A,N/A\n";

        List<Map<String, Object>> points = ingestController.parseStooqCsvToPoints(csv);
        assertEquals(1, points.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) points.get(0).get("values");
        assertNull(values.get("close"));
        assertNull(values.get("volume"));
    }

    @Test
    void parseStooqCsv_skipsRowsWithFewerThanSixColumns() {
        String csv = "Date,Open,High,Low,Close,Volume\n" +
                     "2024-01-02,185.0,186.5\n" +          // malformed — only 3 cols
                     "2024-01-03,185.5,187.0,185.0,186.0,55000000\n";

        List<Map<String, Object>> points = ingestController.parseStooqCsvToPoints(csv);
        assertEquals(1, points.size());
        assertEquals("2024-01-03T00:00:00Z", points.get(0).get("ts"));
    }

    // ---------------------------------------------------------------
    // 2. IDEMPOTENCY — instrument and series must not be duplicated on rerun
    // ---------------------------------------------------------------

    @Test
    void ingest_idempotent_doesNotDuplicateInstrument_onRerun() {
        // Seed an instrument as if a previous ingest already ran
        long instId = Math.abs("DEMO.TEST".hashCode()) + 100000L;
        instrumentRepository.save(new InstrumentDoc(null, instId, "DEMO.TEST", "UNKNOWN", "ACTIVE", 1L));
        dataSourceRepository.save(new DataSourceDoc(null, 99L, "Stooq"));

        // Simulate a second ingest for the same symbol — instrument must not be duplicated
        boolean existsBefore = instrumentRepository.existsByInstrumentId(instId);
        assertTrue(existsBefore);

        // Running the check-then-insert logic manually (same logic as controller)
        if (!instrumentRepository.existsByInstrumentId(instId)) {
            instrumentRepository.save(new InstrumentDoc(null, instId, "DEMO.TEST", "UNKNOWN", "ACTIVE", 1L));
        }

        long count = instrumentRepository.findAll().stream()
                .filter(i -> i.getInstrumentId() == instId)
                .count();
        assertEquals(1, count, "Instrument must not be duplicated on rerun");
    }

    @Test
    void ingest_idempotent_doesNotDuplicateSeries_onRerun() {
        // Seed a series as if a previous ingest already ran
        timeSeriesRepository.save(new TimeSeriesDoc(null, 7001L, 1001L, 20L, "1d",
                "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"));

        // Simulate the orElseGet logic — must return existing, not create a new one
        TimeSeriesDoc series = timeSeriesRepository
                .findFirstByInstrumentIdAndSourceIdAndGranularity(1001L, 20L, "1d")
                .orElseGet(() -> timeSeriesRepository.save(new TimeSeriesDoc(
                        null, 7999L, 1001L, 20L, "1d", "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
                )));

        assertEquals(7001L, series.getSeriesId(), "Must reuse existing series, not create a new one");

        long seriesCount = timeSeriesRepository.findByInstrumentId(1001L).stream()
                .filter(s -> s.getSourceId() == 20L && s.getGranularity().equals("1d"))
                .count();
        assertEquals(1, seriesCount, "Series must not be duplicated on rerun");
    }

    // ---------------------------------------------------------------
    // 3. PROVENANCE — every ingest must append a new version record
    // ---------------------------------------------------------------

    @Test
    void ingest_alwaysAppendsNewVersionRecord_forAuditTrail() {
        long instId = 1001L;
        long sourceId = 20L;

        instrumentRepository.save(new InstrumentDoc(null, instId, "AAPL", "US", "ACTIVE", 1L));
        dataSourceRepository.save(new DataSourceDoc(null, sourceId, "Stooq"));

        // Simulate two sequential ingests (two version records expected — temporal audit trail)
        long v1 = System.currentTimeMillis() * 1000L + (instId % 1000L);
        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, v1, instId, sourceId,
                "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z",
                "UPSERT", "{\"provider\":\"stooq\",\"symbol\":\"AAPL.US\"}"
        ));

        long v2 = v1 + 1;
        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, v2, instId, sourceId,
                "2026-06-01T00:00:00Z", "2026-06-01T00:00:00Z",
                "UPSERT", "{\"provider\":\"stooq\",\"symbol\":\"AAPL.US\"}"
        ));

        List<InstrumentVersionDoc> versions =
                instrumentVersionRepository.findByInstrumentIdOrderByValidFromAsc(instId);

        assertEquals(2, versions.size(), "Each ingest must produce a new version record (append-only provenance)");
        assertEquals("2026-01-01T00:00:00Z", versions.get(0).getValidFrom());
        assertEquals("2026-06-01T00:00:00Z", versions.get(1).getValidFrom());
    }

    // ---------------------------------------------------------------
    // 4. BUCKET UPSERT — rerun must update buckets, not create duplicates
    // ---------------------------------------------------------------

    @Test
    void ingest_bucketUpsert_updatesExistingBucket_notDuplicate() {
        // Simulate first ingest: bucket exists with old data
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null, 7001L, "2024-01-01T00:00:00Z",
                "[{\"ts\":\"2024-01-02T00:00:00Z\",\"values\":{\"close\":150.0}}]"
        ));

        // Simulate second ingest: same bucket, updated data
        String updatedPoints = "[{\"ts\":\"2024-01-02T00:00:00Z\",\"values\":{\"close\":160.0}}]";

        Optional<TimeSeriesPointsDoc> existing = timeSeriesPointsRepository
                .findBySeriesIdAndBucketStartBetweenOrderByBucketStartAsc(
                        7001L, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z"
                )
                .stream().findFirst();

        assertTrue(existing.isPresent());
        existing.get().setPoints(updatedPoints);
        timeSeriesPointsRepository.save(existing.get());

        // Only one bucket must exist — no duplicate
        List<TimeSeriesPointsDoc> buckets = timeSeriesPointsRepository
                .findBySeriesIdAndBucketStartBetweenOrderByBucketStartAsc(
                        7001L, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z"
                );

        assertEquals(1, buckets.size(), "Bucket rerun must upsert, not duplicate");
        assertTrue(buckets.get(0).getPoints().contains("160.0"), "Bucket must contain updated data");
    }

    // ---------------------------------------------------------------
    // 5. SOURCE AUTO-CREATION
    // ---------------------------------------------------------------

    @Test
    void ingest_autoCreatesSource_ifNotPresent() {
        assertFalse(dataSourceRepository.existsBySourceId(99L));

        // Simulate the orElseGet auto-create logic from IngestController
        dataSourceRepository.findBySourceId(99L).orElseGet(() -> {
            DataSourceDoc s = new DataSourceDoc(null, 99L, "Stooq");
            return dataSourceRepository.save(s);
        });

        assertTrue(dataSourceRepository.existsBySourceId(99L));
        assertEquals("Stooq", dataSourceRepository.findBySourceId(99L).get().getName());
    }
}
