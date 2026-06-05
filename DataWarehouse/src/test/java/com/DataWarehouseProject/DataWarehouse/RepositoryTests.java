package com.DataWarehouseProject.DataWarehouse;

import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import com.DataWarehouseProject.DataWarehouse.testsupport.MongoTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RepositoryTests {

    @Autowired private MongoTemplate mongoTemplate;

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentVersionRepository instrumentVersionRepository;
    @Autowired private DataSourceRepository dataSourceRepository;
    @Autowired private InstrumentClassRepository instrumentClassRepository;
    @Autowired private TimeSeriesRepository timeSeriesRepository;
    @Autowired private TimeSeriesPointsRepository timeSeriesPointsRepository;

    @BeforeEach
    void cleanDb() {
        MongoTestSupport.cleanAll(mongoTemplate);
    }

    @Test
    void instrumentVersion_findLatestByValidFromDesc_returnsNewest() {
        // Arrange
        instrumentRepository.save(new InstrumentDoc(null, 1001L, "AAPL", "US", "ACTIVE", 1L));
        dataSourceRepository.save(new DataSourceDoc(null, 10L, "DemoVendor"));

        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, 9001L, 1001L, 10L,
                "2026-01-01T00:00:00Z",
                "2026-03-01T00:00:00Z",
                "UPSERT",
                "{\"sector\":\"Technology\"}"
        ));

        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, 9002L, 1001L, 10L,
                "2026-02-01T00:00:00Z",
                "2026-03-02T00:00:00Z",
                "UPSERT",
                "{\"sector\":\"Technology\",\"currency\":\"USD\"}"
        ));

        // Act
        Optional<InstrumentVersionDoc> latest =
                instrumentVersionRepository.findFirstByInstrumentIdOrderByValidFromDesc(1001L);

        // Assert
        assertTrue(latest.isPresent());
        assertEquals(9002L, latest.get().getVersionId());
        assertEquals("2026-02-01T00:00:00Z", latest.get().getValidFrom());
    }

    @Test
    void timeSeries_findByInstrumentSourceGranularity_returnsSeries() {
        // Arrange
        timeSeriesRepository.save(new TimeSeriesDoc(
                null, 7001L, 1001L, 10L, "1d",
                "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
        ));

        // Act
        Optional<TimeSeriesDoc> series =
                timeSeriesRepository.findFirstByInstrumentIdAndSourceIdAndGranularity(1001L, 10L, "1d");

        // Assert
        assertTrue(series.isPresent());
        assertEquals(7001L, series.get().getSeriesId());
    }

    @Test
    void timeSeriesPoints_findBySeriesIdOrdered_returnsBuckets() {
        // Arrange
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null, 7001L, "2026-03-01T00:00:00Z",
                "[{\"ts\":\"2026-03-01T00:00:00Z\",\"values\":{\"close\":182.0}}]"
        ));
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null, 7001L, "2026-04-01T00:00:00Z",
                "[{\"ts\":\"2026-04-01T00:00:00Z\",\"values\":{\"close\":190.0}}]"
        ));

        // Act
        var buckets = timeSeriesPointsRepository.findBySeriesIdOrderByBucketStartAsc(7001L);

        // Assert
        assertEquals(2, buckets.size());
        assertEquals("2026-03-01T00:00:00Z", buckets.get(0).getBucketStart());
        assertEquals("2026-04-01T00:00:00Z", buckets.get(1).getBucketStart());
    }

    @Test
    void deleteMarker_isAppendOnly_andLatestBecomesDELETE() {
        // Arrange
        instrumentRepository.save(new InstrumentDoc(null, 1001L, "AAPL", "US", "ACTIVE", 1L));
        dataSourceRepository.save(new DataSourceDoc(null, 10L, "DemoVendor"));

        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, 9001L, 1001L, 10L,
                "2026-01-01T00:00:00Z",
                "2026-03-01T00:00:00Z",
                "UPSERT",
                "{\"currency\":\"USD\"}"
        ));

        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, 9002L, 1001L, 10L,
                "2026-03-10T00:00:00Z",
                "2026-03-10T00:00:00Z",
                "DELETE",
                "{}"
        ));

        // Act
        var latest = instrumentVersionRepository.findFirstByInstrumentIdOrderByValidFromDesc(1001L);

        // Assert
        assertTrue(latest.isPresent());
        assertEquals("DELETE", latest.get().getOpType());
        assertEquals(2, instrumentVersionRepository.findAll().size(), "Append-only: no updates, only inserts");
    }
}