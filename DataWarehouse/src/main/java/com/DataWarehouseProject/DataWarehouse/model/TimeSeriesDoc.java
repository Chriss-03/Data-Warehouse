package com.DataWarehouseProject.DataWarehouse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "time_series")
public class TimeSeriesDoc {

    @Id
    private String id; // _id : string

    private long seriesId;
    private long instrumentId;
    private long sourceId;

    private String granularity;

    // JSON string or comma-separated list (your diagram uses string)
    private String indicatorSet;

    public TimeSeriesDoc() {}

    public TimeSeriesDoc(String id, long seriesId, long instrumentId, long sourceId,
                         String granularity, String indicatorSet) {
        this.id = id;
        this.seriesId = seriesId;
        this.instrumentId = instrumentId;
        this.sourceId = sourceId;
        this.granularity = granularity;
        this.indicatorSet = indicatorSet;
    }

    public String getId() { return id; }
    public long getSeriesId() { return seriesId; }
    public long getInstrumentId() { return instrumentId; }
    public long getSourceId() { return sourceId; }
    public String getGranularity() { return granularity; }
    public String getIndicatorSet() { return indicatorSet; }

    public void setId(String id) { this.id = id; }
    public void setSeriesId(long seriesId) { this.seriesId = seriesId; }
    public void setInstrumentId(long instrumentId) { this.instrumentId = instrumentId; }
    public void setSourceId(long sourceId) { this.sourceId = sourceId; }
    public void setGranularity(String granularity) { this.granularity = granularity; }
    public void setIndicatorSet(String indicatorSet) { this.indicatorSet = indicatorSet; }
}