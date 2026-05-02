package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.model.DataSourceDoc;
import com.DataWarehouseProject.DataWarehouse.repository.DataSourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/sources")
public class SourceController {

    private final DataSourceRepository dataSourceRepository;

    public SourceController(DataSourceRepository dataSourceRepository) {
        this.dataSourceRepository = dataSourceRepository;
    }

    // Q3: list all sources (limited info)
    @GetMapping
    public List<Map<String, Object>> listSources() {
        List<DataSourceDoc> sources = dataSourceRepository.findAll();
        List<Map<String, Object>> out = new ArrayList<>();

        for (DataSourceDoc s : sources) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", s.getSourceId());
            row.put("name", s.getName());
            out.add(row);
        }
        return out;
    }

    // Q4: source details by id
    @GetMapping("/{sourceId}")
    public ResponseEntity<?> getSource(@PathVariable long sourceId) {
        return dataSourceRepository.findBySourceId(sourceId)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(Map.of(
                        "sourceId", s.getSourceId(),
                        "name", s.getName()
                )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}