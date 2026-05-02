package com.DataWarehouseProject.DataWarehouse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "instrument_versions")
public class InstrumentVersionDoc {

    @Id
    private String id; // _id : string

    private long versionId;
    private long instrumentId;
    private long sourceId;

    // ISO-8601 datetime string
    private String validFrom;

    // ISO-8601 datetime string
    private String ingestedAt;

    // "UPSERT" or "DELETE"
    private String opType;

    // JSON string (heterogeneous attributes) - matches diagram: attributes : string
    private String attributes;

    public InstrumentVersionDoc() {}

    public InstrumentVersionDoc(String id, long versionId, long instrumentId, long sourceId,
                                String validFrom, String ingestedAt, String opType, String attributes) {
        this.id = id;
        this.versionId = versionId;
        this.instrumentId = instrumentId;
        this.sourceId = sourceId;
        this.validFrom = validFrom;
        this.ingestedAt = ingestedAt;
        this.opType = opType;
        this.attributes = attributes;
    }

    public String getId() { return id; }
    public long getVersionId() { return versionId; }
    public long getInstrumentId() { return instrumentId; }
    public long getSourceId() { return sourceId; }
    public String getValidFrom() { return validFrom; }
    public String getIngestedAt() { return ingestedAt; }
    public String getOpType() { return opType; }
    public String getAttributes() { return attributes; }

    public void setId(String id) { this.id = id; }
    public void setVersionId(long versionId) { this.versionId = versionId; }
    public void setInstrumentId(long instrumentId) { this.instrumentId = instrumentId; }
    public void setSourceId(long sourceId) { this.sourceId = sourceId; }
    public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
    public void setIngestedAt(String ingestedAt) { this.ingestedAt = ingestedAt; }
    public void setOpType(String opType) { this.opType = opType; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
}