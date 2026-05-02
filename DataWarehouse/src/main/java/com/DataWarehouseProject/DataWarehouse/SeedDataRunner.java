package com.DataWarehouseProject.DataWarehouse;

import com.DataWarehouseProject.DataWarehouse.model.*;
import com.DataWarehouseProject.DataWarehouse.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import com.mongodb.client.MongoDatabase;

@Component
public class SeedDataRunner implements CommandLineRunner {

    private final InstrumentClassRepository instrumentClassRepository;
    private final DataSourceRepository dataSourceRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentVersionRepository instrumentVersionRepository;
    private final TimeSeriesRepository timeSeriesRepository;
    private final TimeSeriesPointsRepository timeSeriesPointsRepository;

    private final MongoTemplate mongoTemplate;
    private final Environment env;
    private final MongoDatabaseFactory mongoDbFactory;

    public SeedDataRunner(
            InstrumentClassRepository instrumentClassRepository,
            DataSourceRepository dataSourceRepository,
            InstrumentRepository instrumentRepository,
            InstrumentVersionRepository instrumentVersionRepository,
            TimeSeriesRepository timeSeriesRepository,
            TimeSeriesPointsRepository timeSeriesPointsRepository,

            MongoTemplate mongoTemplate,
            Environment env,
            MongoDatabaseFactory mongoDbFactory
    ) {
        this.instrumentClassRepository = instrumentClassRepository;
        this.dataSourceRepository = dataSourceRepository;
        this.instrumentRepository = instrumentRepository;
        this.instrumentVersionRepository = instrumentVersionRepository;
        this.timeSeriesRepository = timeSeriesRepository;
        this.timeSeriesPointsRepository = timeSeriesPointsRepository;
        this.mongoTemplate = mongoTemplate;
        this.env = env;
        this.mongoDbFactory = mongoDbFactory;
    }

    @Override
    public void run(String... args) {

        System.out.println(">>> SeedDataRunner executed");

        System.out.println(">>> spring.data.mongodb.uri = " + env.getProperty("spring.data.mongodb.uri"));
        System.out.println(">>> spring.data.mongodb.database = " + env.getProperty("spring.data.mongodb.database"));
        System.out.println(">>> Mongo DB name = " + mongoTemplate.getDb().getName());
        MongoDatabase db = mongoDbFactory.getMongoDatabase();
        System.out.println(">>> Factory DB name = " + db.getName());

        // Prevent re-seeding if data already exists
        if (instrumentRepository.existsByInstrumentId(1001L)) {
            return;
        }

        // 1) instrument_classes
        instrumentClassRepository.save(new InstrumentClassDoc(null, 1L, "Equity"));

        // 2) data_sources
        dataSourceRepository.save(new DataSourceDoc(null, 10L, "DemoVendor"));

        // 3) instruments
        instrumentRepository.save(new InstrumentDoc(null, 1001L, "AAPL", "US", "ACTIVE", 1L));

        System.out.println(">>> instruments count = " + instrumentRepository.count());
        System.out.println(">>> instrument 1001 exists? " + instrumentRepository.existsByInstrumentId(1001L));

        // 4) instrument_versions (append-only metadata)
        instrumentVersionRepository.save(new InstrumentVersionDoc(
                null,
                9001L,
                1001L,
                10L,
                "2026-01-01T00:00:00Z",
                "2026-03-07T00:00:00Z",
                "UPSERT",
                "{\"sector\":\"Technology\",\"currency\":\"USD\"}"
        ));

        // 5) time_series (definition)
        timeSeriesRepository.save(new TimeSeriesDoc(
                null,
                7001L,
                1001L,
                10L,
                "1d",
                "[\"open\",\"high\",\"low\",\"close\",\"volume\"]"
        ));

        // 6) time_series_points (bucket with 2 points)
        timeSeriesPointsRepository.save(new TimeSeriesPointsDoc(
                null,
                7001L,
                "2026-03-01T00:00:00Z",
                "[" +
                        "{\"ts\":\"2026-03-01T00:00:00Z\",\"values\":{\"open\":180.0,\"close\":182.0,\"volume\":50000000}}," +
                        "{\"ts\":\"2026-03-02T00:00:00Z\",\"values\":{\"open\":182.0,\"close\":181.5,\"volume\":42000000}}" +
                        "]"
        ));
    }
}