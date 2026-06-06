package com.DataWarehouseProject.DataWarehouse;

import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import com.DataWarehouseProject.DataWarehouse.testsupport.MongoTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UC2 + UC3 API checks using real controllers + real repositories.
 * Requires local MongoDB running on localhost:27017.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
public class ApiControllerTests {

    @Autowired private WebApplicationContext wac;
    @Autowired private MongoTemplate mongoTemplate;

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private InstrumentVersionRepository instrumentVersionRepository;
    @Autowired private DataSourceRepository dataSourceRepository;
    @Autowired private TimeSeriesRepository timeSeriesRepository;
    @Autowired private TimeSeriesPointsRepository timeSeriesPointsRepository;

    private MockMvc mockMvc;

    // Use a seriesId that will NOT collide with SeedDataRunner (7001L)
    private static final long TEST_SERIES_ID   = 8001L;
    private static final long TEST_INST_ID     = 2001L;
    private static final long TEST_SOURCE_ID   = 10L;
    private static final long TEST_VERSION_ID  = 8901L;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
        MongoTestSupport.cleanAll(mongoTemplate);

        dataSourceRepository.save(new DataSourceDoc(null, TEST_SOURCE_ID, "DemoVendor"));

        instrumentRepository.save(new InstrumentDoc(
                null, TEST_INST_ID, "AAPL_TEST", "US", "ACTIVE", 1L));

        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null, TEST_VERSION_ID, TEST_INST_ID, TEST_SOURCE_ID,
                "2026-01-01T00:00:00Z", "2026-03-07T00:00:00Z",
                "UPSERT", "{\"sector\":\"Technology\",\"currency\":\"USD\"}"
        ));

        timeSeriesRepository.save(new TimeSeriesDoc(
                null, TEST_SERIES_ID, TEST_INST_ID, TEST_SOURCE_ID, "1d",
                "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
        ));

        // Bucket: March 2026
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null, TEST_SERIES_ID,
                "2026-03-01T00:00:00Z",
                "[" +
                "{\"ts\":\"2026-03-01T00:00:00Z\",\"values\":{\"open\":180.0,\"close\":182.0,\"volume\":50000000}}," +
                "{\"ts\":\"2026-03-02T00:00:00Z\",\"values\":{\"open\":182.0,\"close\":181.5,\"volume\":42000000}}" +
                "]"
        ));
    }

    // Q1 — list assets returns paginated wrapper
    @Test
    void Q1_listAssets_returnsLimitedInfo() throws Exception {
        mockMvc.perform(get("/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)));
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
        mockMvc.perform(get("/assets/" + TEST_INST_ID).param("sourceId", String.valueOf(TEST_SOURCE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId", is((int) TEST_INST_ID)))
                .andExpect(jsonPath("$.symbol", is("AAPL_TEST")))
                .andExpect(jsonPath("$.latestVersion.versionId", is((int) TEST_VERSION_ID)))
                .andExpect(jsonPath("$.latestVersion.opType", is("UPSERT")))
                .andExpect(jsonPath("$.latestVersion.sourceId", is((int) TEST_SOURCE_ID)))
                .andExpect(jsonPath("$.latestVersion.attributes", containsString("Technology")));
    }

    // Q2 — unknown asset returns 404
    @Test
    void Q2_getAsset_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/assets/999999"))
                .andExpect(status().isNotFound());
    }

    // Q3 — list sources returns paginated wrapper
    @Test
    void Q3_listSources_returnsLimitedInfo() throws Exception {
        mockMvc.perform(get("/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].sourceId", notNullValue()))
                .andExpect(jsonPath("$.content[0].name", notNullValue()))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)));
    }

    // Q4 — source detail enriched
    @Test
    void Q4_getSource_returnsSourceDetails() throws Exception {
        mockMvc.perform(get("/sources/" + TEST_SOURCE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId", is((int) TEST_SOURCE_ID)))
                .andExpect(jsonPath("$.name", is("DemoVendor")))
                .andExpect(jsonPath("$.type", is("synthetic")))
                .andExpect(jsonPath("$.description", containsString("DemoVendor")));
    }

    // Q4 — unknown source returns 404
    @Test
    void Q4_getSource_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/sources/999999"))
                .andExpect(status().isNotFound());
    }

    // Q5 — time series range query, bucket in range
    @Test
    void Q5_fetchTimeSeries_returnsBucketsInRange() throws Exception {
        mockMvc.perform(get("/timeseries")
                        .param("instrumentId", String.valueOf(TEST_INST_ID))
                        .param("sourceId",     String.valueOf(TEST_SOURCE_ID))
                        .param("granularity",  "1d")
                        .param("from",         "2026-03-01T00:00:00Z")
                        .param("to",           "2026-03-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.seriesId",     is((int) TEST_SERIES_ID)))
                .andExpect(jsonPath("$.series.instrumentId", is((int) TEST_INST_ID)))
                .andExpect(jsonPath("$.series.sourceId",     is((int) TEST_SOURCE_ID)))
                .andExpect(jsonPath("$.buckets",             hasSize(1)))
                .andExpect(jsonPath("$.buckets[0].bucketStart", is("2026-03-01T00:00:00Z")))
                .andExpect(jsonPath("$.buckets[0].points",   containsString("\"close\":182.0")));
    }

    // Q5 — out-of-range query returns empty buckets
    @Test
    void Q5_fetchTimeSeries_outOfRange_returnsEmptyBuckets() throws Exception {
        mockMvc.perform(get("/timeseries")
                        .param("instrumentId", String.valueOf(TEST_INST_ID))
                        .param("sourceId",     String.valueOf(TEST_SOURCE_ID))
                        .param("granularity",  "1d")
                        .param("from",         "2020-01-01T00:00:00Z")
                        .param("to",           "2020-12-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets", hasSize(0)));
    }

    // UC3 — analytics summary aggregates
    @Test
    void UC3_analyticsSummary_returnsAggregates() throws Exception {
        mockMvc.perform(get("/analytics/summary")
                        .param("instrumentId", String.valueOf(TEST_INST_ID))
                        .param("sourceId",     String.valueOf(TEST_SOURCE_ID))
                        .param("granularity",  "1d")
                        .param("from",         "2026-03-01T00:00:00Z")
                        .param("to",           "2026-03-31T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrumentId", is((int) TEST_INST_ID)))
                .andExpect(jsonPath("$.sourceId",     is((int) TEST_SOURCE_ID)))
                .andExpect(jsonPath("$.pointsCount",  is(2)))
                .andExpect(jsonPath("$.minClose",     closeTo(181.5, 0.0001)))
                .andExpect(jsonPath("$.maxClose",     closeTo(182.0, 0.0001)))
                .andExpect(jsonPath("$.totalVolume",  is(92000000)));
    }

    // Health check
    @Test
    void health_endpoint_returnsOk() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk());
    }
}
