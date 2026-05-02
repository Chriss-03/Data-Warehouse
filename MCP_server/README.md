# MCP Server — Documentation

This file implements an MCP (Model Context Protocol) server that exposes the Java DataWarehouse REST API as callable tools for an LLM client (Claude Desktop).  
The LLM can call tools like `list_assets`, `fetch_time_series`, `analytics_summary`, etc., and the MCP server forwards those requests to the Spring Boot API (`http://localhost:8080`).

---

## How server.js connects the system together:

- Spring Boot = actual data platform (MongoDB-backed) that stores assets, sources, time-series, analytics.
- MongoDB = persistent storage
- MCP server (this Node server.js) = a bridge that:
  1. Registers a set of tools (functions) with MCP
  2. Receives tool calls from the LLM host (Claude Desktop)
  3. Validates arguments (Zod)
  4. Calls the REST API endpoints
  5. Returns the API response back to the LLM as tool output
