package com.DataWarehouseProject.DataWarehouse;

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

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UC2 + UC3 API checks using real controllers + real repositories.
 * Requires local MongoDB running on localhost:27017.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
public class ApiControllerTests {

    @Autowired private MongoTemplate mongoTemplate;
    @Autowired private MockMvc mockMvc;

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentVersionRepository instrumentVersionRepository;
    @Autowired private DataSourceRepository dataSourceRepository;
    @Autowired private TimeSeriesRepository timeSeriesRepository;
    @Autowired private TimeSeriesPointsRepository timeSeriesPointsRepository;

    @BeforeEach
    void setup() {
        MongoTestSupport.cleanAll(mongoTemplate);

        dataSourceRepository.save(new DataSourceDoc(null, 10L, "DemoVendor"));
        instrumentRepository.save(new InstrumentDoc(null, 1001L, "AAPL", "US", "ACTIVE", 1L));
        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, 9001L, 1001L, 10L,
                "2026-01-01T00:00:00Z", "2026-03-07T00:00:00Z",
                "UPSERT", "{\"sector\":\"Technology\",\"currency\":\"USD\"}"
        ));
        timeSeriesRepository.save(new TimeSeriesDoc(
                null, 7001L, 1001L, 10L, "1d",
                "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
        ));
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null, 7001L, "2026-03-01T00:00:00Z",
                "[" +
                "{\"ts\":\"2026-03-01T00:00:00Z\",\"values\":{\"open\":180.0,\"close\":182.0,\"volume\":50000000}}," +
                "{\"ts\":\"2026-03-02T00:00:00Z\",\"values\":{\"open\":182.0,\"close\":181.5,\"volume\":42000000}}" +
                "]"
        ));
    }

    // Q1 — list assets returns paginated wrapper with content array
    @Test
    void Q1_listAssets_returnsLimitedInfo() throws Exception {
        mockMvc.perform(get("/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].instrumentId", is(1001)))
                .andExpect(jsonPath("$.content[0].symbol", is("AAPL")))
                .andExpect(jsonPath("$.content[0].classId", is(1)))
                .andExpect(jsonPath("$.content[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.totalPages", greaterThanOrEqualTo(1)));
    }

    // Q1 — pagination params respected
    @Test
    void Q1_listAssets_paginationParamsRespected() throws Exception {
        mockMvc.perform(get("/assets").param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.size", is(1)))
                .andExpect(jsonPath("$.page", is(0)));
    }

    // Q2 — full asset detail
    @Test
    void Q2_getAsset_returnsDetailsWithLatestVersion() throws Exception {
        mockMvc.perform(get("/assets/1001").param("sourceId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId", is(1001)))
                .andExpect(jsonPath("$.symbol", is("AAPL")))
                .andExpect(jsonPath("$.latestVersion.versionId", is(9001)))
                .andExpect(jsonPath("$.latestVersion.opType", is("UPSERT")))
                .andExpect(jsonPath("$.latestVersion.sourceId", is(10)))
                .andExpect(jsonPath("$.latestVersion.attributes", containsString("Technology")));
    }

    // Q2 — unknown asset returns 404
    @Test
    void Q2_getAsset_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/assets/9999"))
                .andExpect(status().isNotFound());
    }

    // Q3 — list sources returns paginated wrapper
    @Test
    void Q3_listSources_returnsLimitedInfo() throws Exception {
        mockMvc.perform(get("/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sourceId", is(10)))
                .andExpect(jsonPath("$.content[0].name", is("DemoVendor")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    // Q4 — source detail enriched
    @Test
    void Q4_getSource_returnsSourceDetails() throws Exception {
        mockMvc.perform(get("/sources/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId", is(10)))
                .andExpect(jsonPath("$.name", is("DemoVendor")))
                .andExpect(jsonPath("$.type", is("synthetic")))
                .andExpect(jsonPath("$.description", containsString("DemoVendor")));
    }

    // Q4 — unknown source returns 404
    @Test
    void Q4_getSource_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/sources/9999"))
                .andExpect(status().isNotFound());
    }

    // Q5 — time series range query pushed to DB
    @Test
    void Q5_fetchTimeSeries_returnsBucketsInRange() throws Exception {
        mockMvc.perform(get("/timeseries")
                        .param("instrumentId", "1001")
                        .param("sourceId", "10")
                        .param("granularity", "1d")
                        .param("from", "2026-03-01T00:00:00Z")
                        .param("to", "2026-03-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.seriesId", is(7001)))
                .andExpect(jsonPath("$.series.instrumentId", is(1001)))
                .andExpect(jsonPath("$.series.sourceId", is(10)))
                .andExpect(jsonPath("$.buckets", hasSize(1)))
                .andExpect(jsonPath("$.buckets[0].bucketStart", is("2026-03-01T00:00:00Z")))
                .andExpect(jsonPath("$.buckets[0].points", containsString("\"close\":182.0")));
    }

    // Q5 — out-of-range query returns empty buckets
    @Test
    void Q5_fetchTimeSeries_outOfRange_returnsEmptyBuckets() throws Exception {
        mockMvc.perform(get("/timeseries")
                        .param("instrumentId", "1001")
                        .param("sourceId", "10")
                        .param("granularity", "1d")
                        .param("from", "2020-01-01T00:00:00Z")
                        .param("to", "2020-12-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets", hasSize(0)));
    }

    // UC3 — analytics summary aggregates
    @Test
    void UC3_analyticsSummary_returnsAggregates() throws Exception {
        mockMvc.perform(get("/analytics/summary")
                        .param("instrumentId", "1001")
                        .param("sourceId", "10")
                        .param("granularity", "1d")
                        .param("from", "2026-03-01T00:00:00Z")
                        .param("to", "2026-03-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId", is(1001)))
                .andExpect(jsonPath("$.sourceId", is(10)))
                .andExpect(jsonPath("$.pointsCount", is(2)))
                .andExpect(jsonPath("$.minClose", closeTo(181.5, 0.0001)))
                .andExpect(jsonPath("$.maxClose", closeTo(182.0, 0.0001)))
                .andExpect(jsonPath("$.totalVolume", is(92000000)));
    }

    // UC3 — analytics for unknown series returns 404 or empty
    @Test
    void UC3_analyticsSummary_unknownSeries_returnsError() throws Exception {
        mockMvc.perform(get("/analytics/summary")
                        .param("instrumentId", "9999")
                        .param("sourceId", "10")
                        .param("granularity", "1d")
                        .param("from", "2026-03-01T00:00:00Z")
                        .param("to", "2026-03-31T00:00:00Z"))
                .andExpect(status().is5xxServerError());
    }

    // Health check
    @Test
    void health_endpoint_returnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }
}
