package com.DataWarehouseProject.DataWarehouse.testsupport;

import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;

public final class MongoTestSupport {

    private MongoTestSupport() {}

    public static void cleanAll(MongoTemplate mongoTemplate) {
        List<String> collections = List.of(
                "instrument_classes",
                "data_sources",
                "instruments",
                "instrument_versions",
                "time_series",
                "time_series_points"
        );

        for (String c : collections) {
            if (mongoTemplate.collectionExists(c)) {
                mongoTemplate.dropCollection(c);
            }
        }
    }
}