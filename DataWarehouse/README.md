# DataWarehouse - Features Guide

This project exposes a REST API over a MongoDB-backed financial data warehouse.
It supports:
- assets/instruments
- data sources (vendors)
- time series (per asset + source + granularity)
- temporal (append-only) metadata versions and delete markers (tombstones)

Base URL (local):
- `http://localhost:8080`

---

## How to use the examples

### 1) Method + URL
Example:

- `GET /assets/1001`

That means:
- HTTP method: `GET`
- path: `/assets/1001`

In Postman you must use the full URL:
- `GET http://localhost:8080/assets/1001`

### 2) Path parameters
Example:
- `/assets/1001`

`1001` is a path parameter (part of the URL path).  
Here it represents `instrumentId = 1001`.

### 3) Query parameters
Example:
- `/assets/1001?sourceId=10&asOf=2026-02-01T00:00:00Z`

Everything after `?` is query parameters:
- `sourceId=10`
- `asOf=...`

`&` separates parameters.

### 4) Date/time format
All date-time strings use ISO-8601 UTC format:
- `YYYY-MM-DDTHH:MM:SSZ`
  Example:
- `2026-03-01T00:00:00Z`

---

# Q1 — List all assets

### Request
`GET http://localhost:8080/assets`

### What it returns
A list of assets with minimal fields for listing:
- `instrumentId`
- `symbol`
- `classId`
- `status`

### Why this exists
This is the overview query: show all assets quickly.

---

# Q2 — Asset details by identifier (latest OR as-of time)

## Q2a) Latest details (default)

### Request
`GET http://localhost:8080/assets/1001?sourceId=10`

### Meaning
- `GET` → retrieve data
- `/assets` → asset resource collection
- `/1001` → the asset identifier (`instrumentId = 1001`)
- `?sourceId=10` → choose which data vendor/source the version is coming from

### What it returns
- core instrument fields (`instrumentId`, `symbol`, `region`, `status`, `classId`)
- plus `latestVersion` (the most recent `instrument_versions` record)
    - includes: `validFrom`, `ingestedAt`, `opType`, `attributes`

## Q2b) As-of time (temporal query)

### Request
`GET http://localhost:8080/assets/1001?sourceId=10&asOf=2026-02-01T00:00:00Z`

### Meaning
- `GET` → retrieve
- `/assets/1001` → asset with instrumentId = 1001
- `sourceId=10` → select vendor/source 10
- `asOf=2026-02-01T00:00:00Z` → “give me the asset state that was valid at this time”

### How it works
The system finds the newest version record such that:
- `validFrom <= asOf`
  and returns that version.

### Why this exists
This is the temporal paradigm requirement: you can query historical state.

---

# Temporal deletion (delete marker / tombstone)

### Request
`POST http://localhost:8080/assets/1001/delete?sourceId=10&validFrom=2026-03-10T00:00:00Z`

### Meaning
- `POST` → create something new (we are inserting a new record)
- `/assets/1001/delete` → create a delete-marker event for instrument 1001
- `sourceId=10` → the vendor/source that issued the delete view
- `validFrom=...` → from this time onward, the instrument is considered deleted (for that source)

### What it does in storage
It inserts a new document into `instrument_versions` with:
- `opType = "DELETE"`
- `validFrom = <provided validFrom>`
- append-only: nothing is actually deleted

### Why this exists
This satisfies the requirement: “deletion is a marker record effective from a date”.

---

# Q3 — List all data sources (limited info)

### Request
`GET http://localhost:8080/sources`

### Meaning
- `GET` → retrieve
- `/sources` → collection of data providers/vendors

### What it returns
A list of:
- `sourceId`
- `name`

---

# Q4 — Data source details by identifier

### Request
`GET http://localhost:8080/sources/10`

### Meaning
- `GET` → retrieve
- `/sources` → data sources collection
- `/10` → `sourceId = 10`

### What it returns
Details for that source (currently: `sourceId`, `name`).

---

# Q5 — Time series for a given asset + source (+ date range)

### Request
`GET http://localhost:8080/timeseries?instrumentId=1001&sourceId=10&granularity=1d&from=2026-03-01T00:00:00Z&to=2026-03-31T00:00:00Z`

### Meaning
- `GET` → retrieve
- `/timeseries` → time-series endpoint
- `instrumentId=1001` → select the asset
- `sourceId=10` → select the vendor/source
- `granularity=1d` → choose frequency (examples: `1d`, `1h`, `5m`)
- `from=...` → start of requested range (inclusive)
- `to=...` → end of requested range (inclusive)

### What it returns
- `series` metadata:
    - `seriesId`, `instrumentId`, `sourceId`, `granularity`, `indicatorSet`
- `buckets`:
    - each bucket has `bucketStart`
    - plus `points` (stored as a JSON string in DB, returned as-is)

### Notes about indicatorSet and points
- `indicatorSet` describes which indicators exist (e.g. OHLCV).
- Each point is conceptually `{ts, values}`:
    - `ts` = timestamp
    - `values` = map of indicator -> numeric value

---

## Troubleshooting (common issues)

### 404 Not Found
- controller not picked up by Spring (wrong package line), or server not restarted

### Connection refused
- server not running, or wrong port

### Empty buckets
- no points inserted for that range, or date strings don’t match the stored bucketStart format

---

## Collections used (MongoDB)
- `instrument_classes`
- `instruments`
- `data_sources`
- `instrument_versions` (append-only temporal versions + delete markers)
- `time_series`
- `time_series_points` (bucketed points)