package com.DataWarehouseProject.DataWarehouse.repository;

import com.DataWarehouseProject.DataWarehouse.model.DataSourceDoc;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface DataSourceRepository extends MongoRepository<DataSourceDoc, String> {
    Optional<DataSourceDoc> findBySourceId(long sourceId);
    boolean existsBySourceId(long sourceId);
}