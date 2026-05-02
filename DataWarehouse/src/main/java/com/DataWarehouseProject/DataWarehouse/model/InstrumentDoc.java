package com.DataWarehouseProject.DataWarehouse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "instruments")
public class InstrumentDoc {

    @Id
    private String id;  // _id : string

    private long instrumentId;
    private String symbol;
    private String region;
    private String status;
    private long classId;

    public InstrumentDoc() {}

    public InstrumentDoc(String id, long instrumentId, String symbol, String region, String status, long classId) {
        this.id = id;
        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.region = region;
        this.status = status;
        this.classId = classId;
    }

    public String getId() { return id; }
    public long getInstrumentId() { return instrumentId; }
    public String getSymbol() { return symbol; }
    public String getRegion() { return region; }
    public String getStatus() { return status; }
    public long getClassId() { return classId; }

    public void setId(String id) { this.id = id; }
    public void setInstrumentId(long instrumentId) { this.instrumentId = instrumentId; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public void setRegion(String region) { this.region = region; }
    public void setStatus(String status) { this.status = status; }
    public void setClassId(long classId) { this.classId = classId; }
}