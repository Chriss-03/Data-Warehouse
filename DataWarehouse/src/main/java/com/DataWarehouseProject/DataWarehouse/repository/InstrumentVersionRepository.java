package com.DataWarehouseProject.DataWarehouse.repository;

import com.DataWarehouseProject.DataWarehouse.model.InstrumentVersionDoc;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InstrumentVersionRepository extends MongoRepository<InstrumentVersionDoc, String> {

    // Latest version for an instrument (regardless of vendor)
    Optional<InstrumentVersionDoc> findFirstByInstrumentIdOrderByValidFromDesc(long instrumentId);

    // Latest version for an instrument from a specific source
    Optional<InstrumentVersionDoc> findFirstByInstrumentIdAndSourceIdOrderByValidFromDesc(long instrumentId, long sourceId);

    // All versions (useful for debugging/history endpoints)
    List<InstrumentVersionDoc> findByInstrumentIdOrderByValidFromAsc(long instrumentId);

    // ---- Temporal ("as-of") queries ----
    // newest version with validFrom <= asOf, regardless of source
    @Query(value = "{ 'instrumentId': ?0, 'validFrom': { $lte: ?1 } }", sort = "{ 'validFrom': -1 }")
    Optional<InstrumentVersionDoc> findAsOfNoSource(long instrumentId, String asOf);

    // newest version with validFrom <= asOf, for a specific source
    @Query(value = "{ 'instrumentId': ?0, 'sourceId': ?1, 'validFrom': { $lte: ?2 } }", sort = "{ 'validFrom': -1 }")
    Optional<InstrumentVersionDoc> findAsOf(long instrumentId, long sourceId, String asOf);
}