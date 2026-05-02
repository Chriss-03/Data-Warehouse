package com.DataWarehouseProject.DataWarehouse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "instrument_classes")
public class InstrumentClassDoc {

    @Id
    private String id;   // _id : string

    private long classId;
    private String name;

    public InstrumentClassDoc() {}

    public InstrumentClassDoc(String id, long classId, String name) {
        this.id = id;
        this.classId = classId;
        this.name = name;
    }

    public String getId() { return id; }
    public long getClassId() { return classId; }
    public String getName() { return name; }

    public void setId(String id) { this.id = id; }
    public void setClassId(long classId) { this.classId = classId; }
    public void setName(String name) { this.name = name; }
}