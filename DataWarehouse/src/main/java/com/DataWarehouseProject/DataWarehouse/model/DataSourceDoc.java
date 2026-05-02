package com.DataWarehouseProject.DataWarehouse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "data_sources")
public class DataSourceDoc {

    @Id
    private String id; // _id : string

    private long sourceId;
    private String name;

    public DataSourceDoc() {}

    public DataSourceDoc(String id, long sourceId, String name) {
        this.id = id;
        this.sourceId = sourceId;
        this.name = name;
    }

    public String getId() { return id; }
    public long getSourceId() { return sourceId; }
    public String getName() { return name; }

    public void setId(String id) { this.id = id; }
    public void setSourceId(long sourceId) { this.sourceId = sourceId; }
    public void setName(String name) { this.name = name; }
}