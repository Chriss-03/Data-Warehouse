package com.DataWarehouseProject.DataWarehouse.repository;

import com.DataWarehouseProject.DataWarehouse.model.InstrumentDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface InstrumentRepository extends MongoRepository<InstrumentDoc, String> {
    Optional<InstrumentDoc> findByInstrumentId(long instrumentId);
    boolean existsByInstrumentId(long instrumentId);
}