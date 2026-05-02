package com.DataWarehouseProject.DataWarehouse.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "time_series_points")
public class TimeSeriesPointsDoc {

    @Id
    private String id; // _id : string

    private long seriesId;

    // ISO-8601 datetime string (bucket start)
    private String bucketStart;

    // JSON string holding array of {ts, values} points (diagram uses string)
    private String points;

    public TimeSeriesPointsDoc() {}

    public TimeSeriesPointsDoc(String id, long seriesId, String bucketStart, String points) {
        this.id = id;
        this.seriesId = seriesId;
        this.bucketStart = bucketStart;
        this.points = points;
    }

    public String getId() { return id; }
    public long getSeriesId() { return seriesId; }
    public String getBucketStart() { return bucketStart; }
    public String getPoints() { return points; }

    public void setId(String id) { this.id = id; }
    public void setSeriesId(long seriesId) { this.seriesId = seriesId; }
    public void setBucketStart(String bucketStart) { this.bucketStart = bucketStart; }
    public void setPoints(String points) { this.points = points; }
}