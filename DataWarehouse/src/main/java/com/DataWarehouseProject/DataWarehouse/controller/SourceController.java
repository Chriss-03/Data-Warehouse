package com.DataWarehouseProject.DataWarehouse.controller;

import com.DataWarehouseProject.DataWarehouse.model.DataSourceDoc;
import com.DataWarehouseProject.DataWarehouse.repository.DataSourceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    // Supports optional pagination:
    //   GET /sources                   -> all sources, up to 20 (default)
    //   GET /sources?page=0&size=5     -> first 5 sources
    @GetMapping
    public Map<String, Object> listSources(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var pageable = PageRequest.of(page, size, Sort.by("sourceId").ascending());
        var pageResult = dataSourceRepository.findAll(pageable);

        List<Map<String, Object>> content = new ArrayList<>();
        for (DataSourceDoc s : pageResult.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceId", s.getSourceId());
            row.put("name",     s.getName());
            content.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content",       content);
        out.put("page",          pageResult.getNumber());
        out.put("size",          pageResult.getSize());
        out.put("totalElements", pageResult.getTotalElements());
        out.put("totalPages",    pageResult.getTotalPages());
        return out;
    }

    // Q4: source details by id — enriched response
    // Example: GET /sources/10
    @GetMapping("/{sourceId}")
    public ResponseEntity<?> getSource(@PathVariable long sourceId) {
        return dataSourceRepository.findBySourceId(sourceId)
                .<ResponseEntity<?>>map(s -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("sourceId",    s.getSourceId());
                    out.put("name",        s.getName());
                    out.put("type",        s.getName().equalsIgnoreCase("DemoVendor") ? "synthetic" : "market");
                    out.put("description", "Financial data provider: " + s.getName());
                    return ResponseEntity.ok(out);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
