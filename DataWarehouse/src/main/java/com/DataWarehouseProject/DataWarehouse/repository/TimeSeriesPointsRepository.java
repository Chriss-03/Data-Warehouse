package com.DataWarehouseProject.DataWarehouse.repository;

import com.DataWarehouseProject.DataWarehouse.model.TimeSeriesPointsDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface TimeSeriesPointsRepository extends MongoRepository<TimeSeriesPointsDoc, String> {

    List<TimeSeriesPointsDoc> findBySeriesIdAndBucketStartBetweenOrderByBucketStartAsc(
            long seriesId,
            String fromBucketStart,
            String toBucketStart
    );

    List<TimeSeriesPointsDoc> findBySeriesIdOrderByBucketStartAsc(long seriesId);
}