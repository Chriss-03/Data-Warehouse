package com.DataWarehouseProject.DataWarehouse.repository;

import com.DataWarehouseProject.DataWarehouse.model.InstrumentClassDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface InstrumentClassRepository extends MongoRepository<InstrumentClassDoc, String> {
    Optional<InstrumentClassDoc> findByClassId(long classId);
    boolean existsByClassId(long classId);
}