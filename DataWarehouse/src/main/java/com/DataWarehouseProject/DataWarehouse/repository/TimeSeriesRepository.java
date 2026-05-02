package com.DataWarehouseProject.DataWarehouse.repository;

import com.DataWarehouseProject.DataWarehouse.model.TimeSeriesDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface TimeSeriesRepository extends MongoRepository<TimeSeriesDoc, String> {

    Optional<TimeSeriesDoc> findFirstByInstrumentIdAndSourceIdAndGranularity(long instrumentId, long sourceId, String granularity);

    List<TimeSeriesDoc> findByInstrumentId(long instrumentId);
}