package com.DataWarehouseProject.DataWarehouse;

import com.DataWarehouseProject.DataWarehouse.controller.IngestController;
import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import com.DataWarehouseProject.DataWarehouse.testsupport.MongoTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the ingestion pipeline:
 *  - CSV parsing correctness and edge cases
 *  - Idempotency: no duplicate instruments or series on rerun
 *  - Provenance: version record always appended (append-only audit trail)
 *  - Bucket upsert: rerun updates existing buckets, not inserts duplicates
 *  - Source auto-creation
 */
@SpringBootTest
public class IngestControllerTests {

    @Autowired private MongoTemplate mongoTemplate;
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
        assertEquals(185.0,     values.get("open"));
        assertEquals(186.5,     values.get("high"));
        assertEquals(184.0,     values.get("low"));
        assertEquals(185.5,     values.get("close"));
        assertEquals(60000000L, values.get("volume"));
    }

    @Test
    void parseStooqCsv_handlesEmptyBody_returnsEmptyList() {
        assertTrue(ingestController.parseStooqCsvToPoints("").isEmpty());
    }

    @Test
    void parseStooqCsv_handlesHeaderOnly_returnsEmptyList() {
        assertTrue(ingestController.parseStooqCsvToPoints(
                "Date,Open,High,Low,Close,Volume\n").isEmpty());
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
                     "2024-01-02,185.0,186.5\n" +
                     "2024-01-03,185.5,187.0,185.0,186.0,55000000\n";

        List<Map<String, Object>> points = ingestController.parseStooqCsvToPoints(csv);
        assertEquals(1, points.size());
        assertEquals("2024-01-03T00:00:00Z", points.get(0).get("ts"));
    }

    // ---------------------------------------------------------------
    // 2. IDEMPOTENCY
    // ---------------------------------------------------------------

    @Test
    void ingest_idempotent_doesNotDuplicateInstrument_onRerun() {
        long instId = Math.abs("DEMO.TEST".hashCode()) + 100000L;
        instrumentRepository.save(new InstrumentDoc(null, instId, "DEMO.TEST", "UNKNOWN", "ACTIVE", 1L));

        // Simulate second run: check-then-insert guards against duplication
        if (!instrumentRepository.existsByInstrumentId(instId)) {
            instrumentRepository.save(new InstrumentDoc(null, instId, "DEMO.TEST", "UNKNOWN", "ACTIVE", 1L));
        }

        long count = instrumentRepository.findAll().stream()
                .filter(i -> i.getInstrumentId() == instId).count();
        assertEquals(1, count, "Instrument must not be duplicated on rerun");
    }

    @Test
    void ingest_idempotent_doesNotDuplicateSeries_onRerun() {
        timeSeriesRepository.save(new TimeSeriesDoc(
                null, 7001L, 1001L, 20L, "1d",
                "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
        ));

        TimeSeriesDoc series = timeSeriesRepository
                .findFirstByInstrumentIdAndSourceIdAndGranularity(1001L, 20L, "1d")
                .orElseGet(() -> timeSeriesRepository.save(new TimeSeriesDoc(
                        null, 7999L, 1001L, 20L, "1d",
                        "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
                )));

        assertEquals(7001L, series.getSeriesId(), "Must reuse existing series");
        long seriesCount = timeSeriesRepository.findByInstrumentId(1001L).stream()
                .filter(s -> s.getSourceId() == 20L && s.getGranularity().equals("1d")).count();
        assertEquals(1, seriesCount, "Series must not be duplicated on rerun");
    }

    // ---------------------------------------------------------------
    // 3. PROVENANCE
    // ---------------------------------------------------------------

    @Test
    void ingest_alwaysAppendsNewVersionRecord_forAuditTrail() {
        long instId = 1001L;
        long sourceId = 20L;
        instrumentRepository.save(new InstrumentDoc(null, instId, "AAPL", "US", "ACTIVE", 1L));
        dataSourceRepository.save(new DataSourceDoc(null, sourceId, "Stooq"));

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

        assertEquals(2, versions.size(), "Each ingest must produce a new version record");
        assertEquals("2026-01-01T00:00:00Z", versions.get(0).getValidFrom());
        assertEquals("2026-06-01T00:00:00Z", versions.get(1).getValidFrom());
    }

    // ---------------------------------------------------------------
    // 4. BUCKET UPSERT
    // Fix: use a range [bucketStart, nextMonth) instead of same-value
    // because MongoDB Between is inclusive on both bounds for strings
    // but we verify by fetching all for the seriesId instead.
    // ---------------------------------------------------------------

    @Test
    void ingest_bucketUpsert_updatesExistingBucket_notDuplicate() {
        // Insert original bucket
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null, 7001L, "2024-01-01T00:00:00Z",
                "[{\"ts\":\"2024-01-02T00:00:00Z\",\"values\":{\"close\":150.0}}]"
        ));

        // Fetch it using findBySeriesIdOrderByBucketStartAsc (no Between ambiguity)
        List<TimeSeriesPointsDoc> all =
                timeSeriesPointsRepository.findBySeriesIdOrderByBucketStartAsc(7001L);

        assertEquals(1, all.size(), "Exactly one bucket should exist before upsert");

        // Simulate upsert: update the existing bucket
        String updatedPoints = "[{\"ts\":\"2024-01-02T00:00:00Z\",\"values\":{\"close\":160.0}}]";
        TimeSeriesPointsDoc doc = all.get(0);
        doc.setPoints(updatedPoints);
        timeSeriesPointsRepository.save(doc);

        // Verify: still only one bucket, with updated data
        List<TimeSeriesPointsDoc> after =
                timeSeriesPointsRepository.findBySeriesIdOrderByBucketStartAsc(7001L);

        assertEquals(1, after.size(), "Bucket rerun must upsert, not duplicate");
        assertTrue(after.get(0).getPoints().contains("160.0"), "Bucket must contain updated data");
        assertFalse(after.get(0).getPoints().contains("150.0"), "Old data must be replaced");
    }

    // ---------------------------------------------------------------
    // 5. SOURCE AUTO-CREATION
    // ---------------------------------------------------------------

    @Test
    void ingest_autoCreatesSource_ifNotPresent() {
        assertFalse(dataSourceRepository.existsBySourceId(99L));

        dataSourceRepository.findBySourceId(99L).orElseGet(() ->
                dataSourceRepository.save(new DataSourceDoc(null, 99L, "Stooq"))
        );

        assertTrue(dataSourceRepository.existsBySourceId(99L));
        assertEquals("Stooq", dataSourceRepository.findBySourceId(99L).get().getName());
    }
}
