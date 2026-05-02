package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.model.InstrumentDoc;
import com.DataWarehouseProject.DataWarehouse.model.InstrumentVersionDoc;
import com.DataWarehouseProject.DataWarehouse.repository.InstrumentRepository;
import com.DataWarehouseProject.DataWarehouse.repository.InstrumentVersionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/assets")
public class AssetController {

    private final InstrumentRepository instrumentRepository;
    private final InstrumentVersionRepository instrumentVersionRepository;

    public AssetController(InstrumentRepository instrumentRepository,
                           InstrumentVersionRepository instrumentVersionRepository) {
        this.instrumentRepository = instrumentRepository;
        this.instrumentVersionRepository = instrumentVersionRepository;
    }

    // Q1: list all assets (limited info)
    @GetMapping
    public List<Map<String, Object>> listAssets() {
        List<InstrumentDoc> instruments = instrumentRepository.findAll();
        List<Map<String, Object>> out = new ArrayList<>();

        for (InstrumentDoc i : instruments) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("instrumentId", i.getInstrumentId());
            row.put("symbol", i.getSymbol());
            row.put("classId", i.getClassId());
            row.put("status", i.getStatus());
            out.add(row);
        }
        return out;
    }

    // Q2: asset details by id
    // Examples:
    //  - Latest: GET /assets/1001?sourceId=10
    //  - As-of:  GET /assets/1001?sourceId=10&asOf=2026-02-01T00:00:00Z
    @GetMapping("/{instrumentId}")
    public ResponseEntity<?> getAsset(@PathVariable long instrumentId,
                                      @RequestParam(required = false) Long sourceId,
                                      @RequestParam(required = false) String asOf) {

        Optional<InstrumentDoc> instrumentOpt = instrumentRepository.findByInstrumentId(instrumentId);
        if (instrumentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        InstrumentDoc instrument = instrumentOpt.get();

        Optional<InstrumentVersionDoc> versionOpt;

        if (asOf != null && !asOf.isBlank()) {
            // AS-OF temporal query
            versionOpt = (sourceId == null)
                    ? instrumentVersionRepository.findAsOfNoSource(instrumentId, asOf)
                    : instrumentVersionRepository.findAsOf(instrumentId, sourceId, asOf);
        } else {
            // LATEST query (existing behavior)
            versionOpt = (sourceId == null)
                    ? instrumentVersionRepository.findFirstByInstrumentIdOrderByValidFromDesc(instrumentId)
                    : instrumentVersionRepository.findFirstByInstrumentIdAndSourceIdOrderByValidFromDesc(instrumentId, sourceId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("instrumentId", instrument.getInstrumentId());
        out.put("symbol", instrument.getSymbol());
        out.put("region", instrument.getRegion());
        out.put("status", instrument.getStatus());
        out.put("classId", instrument.getClassId());

        if (versionOpt.isPresent()) {
            InstrumentVersionDoc v = versionOpt.get();

            out.put("latestVersion", Map.of(
                    "versionId", v.getVersionId(),
                    "sourceId", v.getSourceId(),
                    "validFrom", v.getValidFrom(),
                    "ingestedAt", v.getIngestedAt(),
                    "opType", v.getOpType(),
                    "attributes", v.getAttributes()
            ));

            out.put("isDeletedAsOf", "DELETE".equalsIgnoreCase(v.getOpType()));
        } else {
            out.put("latestVersion", null);
            out.put("isDeletedAsOf", false);
        }

        return ResponseEntity.ok(out);
    }

    // Temporal deletion: insert a DELETE marker (tombstone) version.
    // Example: POST /assets/1001/delete?sourceId=10&validFrom=2026-03-10T00:00:00Z
    @PostMapping("/{instrumentId}/delete")
    public ResponseEntity<?> deleteAsset(@PathVariable long instrumentId,
                                         @RequestParam long sourceId,
                                         @RequestParam String validFrom) {

        long versionId = System.currentTimeMillis();

        InstrumentVersionDoc tombstone = new InstrumentVersionDoc(
                null,
                versionId,
                instrumentId,
                sourceId,
                validFrom,
                Instant.now().toString(),
                "DELETE",
                "{}"
        );

        instrumentVersionRepository.save(tombstone);

        return ResponseEntity.ok(Map.of(
                "message", "Delete marker inserted",
                "instrumentId", instrumentId,
                "sourceId", sourceId,
                "validFrom", validFrom,
                "versionId", versionId
        ));
    }
}