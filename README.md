# Data Warehouse

This project is a financial market data platform that can ingest market data from external providers, store it in a NoSQL (MongoDB) temporal data warehouse, expose it via a REST API, provide analytics endpoints, and integrate an LLM assistant via MCP so the LLM can query the platform as tools.

It is designed to satisfy the following requirements:
- UC1: ingest external vendor market data (REST)
- UC2: expose stored data via REST queries (assets / sources / time series)
- UC3: analytics & mining-friendly outputs (summary stats, trends inputs)
- UC4: LLM assistant integrated via MCP, grounded in platform data

---

## Repository structure

- `DataWarehouse/`  
  Spring Boot application (Java) exposing the REST API and persisting data to MongoDB.

- `MCP_server/`  
  Node.js MCP server (server.js) that registers tools and forwards LLM tool calls to the Java REST API.

---

## Architecture overview

### Core runtime components
1. **MongoDB**  
   Stores the warehouse collections:
   - instruments (assets)
   - instrument_versions (temporal versions / opType markers)
   - data_sources (vendors / provenance)
   - time_series (series metadata: instrument + source + granularity + indicatorSet)
   - time_series_points (bucketed points as stored JSON)

2. **Spring Boot API (Java)**  
   Implements the platform logic:
   - ingestion endpoint(s) for vendors (example: Stooq)
   - asset discovery and detail endpoints
   - time-series retrieval endpoint
   - analytics summary endpoint

3. **MCP Server (Node.js)**  
   Exposes the platform’s capabilities as MCP tools:
   - list assets, get asset, list sources, get source
   - fetch time series, analytics summary
   - ingest external data (stooq daily)

4. **MCP Host (Claude Desktop)**  
   Calls the tools in natural language. The assistant becomes “data-grounded” by relying on tool outputs.

---

## Data model

### Assets / Instruments
An instrument represents a financial asset (stock, bond, crypto, etc.).  
Core fields include:
- `instrumentId` (internal ID)
- `symbol` (ticker like AAPL, TSLA.US)
- `region`
- `status` (ACTIVE / deleted marker logic)
- `classId` (instrument type, e.g. equity)

### Temporal paradigm
Instruments are temporal:
- no in-place updates
- “updates” create new InstrumentVersion records (`opType=UPSERT`)
- “deletion” creates a marker version (`opType=DELETE`) effective from `validFrom`

### Sources
A source is a vendor/provider (DemoVendor, Stooq, etc.).  
Every ingested dataset is tagged with `sourceId` so provenance is traceable.

### Time series
A time series is defined by:
- `instrumentId`
- `sourceId`
- `granularity` (label, e.g. `1d`)
- `indicatorSet` (which columns exist: open/high/low/close/volume, etc.)

A time series stores its actual values as multiple time-series buckets (documents) containing:
- `bucketStart` (beginning of bucket)
- `points` (JSON string of daily points inside that bucket)

This “bucketed points” format is efficient for:
- REST retrieval
- analytics/ML pipeline feeding (Spark-friendly)

---

## REST API capabilities (UC2 / UC3)

Endpoints implemented in the Spring Boot app:
- `GET /assets` — list assets (limited fields)
- `GET /assets/{instrumentId}` — asset details (optionally temporal `sourceId`, `asOf`)
- `GET /sources` — list sources
- `GET /sources/{sourceId}` — source details
- `GET /timeseries?...` — retrieve time-series buckets in a date range
- `GET /analytics/summary?...` — compute min/max/avg/return/volume over range
- `POST /ingest/stooq/daily?...` — ingest OHLCV from Stooq (external provider)

---

## LLM integration via MCP (UC4)

The platform includes an MCP server which registers platform actions as tools:
- `list_assets`
- `get_asset`
- `list_sources`
- `get_source`
- `fetch_time_series`
- `analytics_summary`
- `ingest_stooq_daily`

Claude Desktop can call these tools automatically, and then explain results using platform-returned data.

---

## How to run

Run three things:

1. MongoDB (brew services start mongodb-community@7.0)
2. Spring Boot DataWarehouse app (run the Java project from /DataWarehouse)
3. MCP server (run Claude Desktop or other LLM)

Detailed step-by-step instructions are be placed in:
- `DataWarehouse/README.md`
- `MCP_server/README.md`

---

## Manual checks

Run these commands in the terminal:

1. mongosh (MongoDB shell)
2. use DataWarehouse (select database)
3. show collections (observe what is contained)

---

## Notes

- The system is built for extensibility:
  - add more vendors (Nasdaq Data Link, Bloomberg, etc.) by implementing additional ingestion adapters
  - extend `indicatorSet` and asset attributes without schema migrations (MongoDB)
  - add new analytics tools/endpoints and expose them via MCP

---