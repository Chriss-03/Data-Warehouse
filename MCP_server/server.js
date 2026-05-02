import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const API_BASE = process.env.API_BASE ?? "http://localhost:8080";

console.error("=== MCP SERVER STARTED ===");
console.error("=== API_BASE:", API_BASE, "===");

async function httpJson(url, options = {}) {
  const res = await fetch(url, {
    ...options,
    headers: { ...(options.headers || {}), Accept: "application/json" },
  });

  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }

  if (!res.ok) {
    throw new Error(
      `HTTP ${res.status} ${res.statusText} from ${url}: ${
        typeof body === "string" ? body : JSON.stringify(body)
      }`
    );
  }
  return body;
}

/**
 * Extract tool arguments robustly.
 *
 * Different SDK/host versions may call handlers like:
 *   fn(args)
 *   fn(ctx, args)
 *   fn(ctx)               // ctx contains request metadata
 *
 * We detect the argument object by looking for expected keys.
 */
function pickArgs(params, expectedKeys) {
  for (const p of params) {
    if (!p || typeof p !== "object") continue;

    // Some SDKs wrap args under .arguments
    if (p.arguments && typeof p.arguments === "object") {
      const a = p.arguments;
      if (expectedKeys.some((k) => k in a)) return a;
    }

    // Or args are passed directly
    if (expectedKeys.some((k) => k in p)) return p;
  }
  return {};
}

function logParams(toolName, params) {
  const safe = params.map((p) => {
    if (p === null || p === undefined) return p;
    if (typeof p !== "object") return p;
    const out = {};
    for (const k of Object.keys(p)) {
      if (k === "signal") out.signal = "[AbortSignal]";
      else if (k === "annotations") out.annotations = "[annotations]";
      else out[k] = p[k];
    }
    return out;
  });
  console.error(`[${toolName}] handler params snapshot:`, JSON.stringify(safe));
}

const server = new McpServer({ name: "dwh-platform-mcp", version: "1.1.0" });

// -------------------- Schemas (Zod) --------------------

const GetAssetSchema = z.object({
  instrumentId: z.coerce.number().int().positive(),
  sourceId: z.coerce.number().int().positive().optional(),
  asOf: z.string().optional(),
});

const GetSourceSchema = z.object({
  sourceId: z.coerce.number().int().positive(),
});

const FetchTimeSeriesSchema = z.object({
  instrumentId: z.coerce.number().int().positive(),
  sourceId: z.coerce.number().int().positive(),
  granularity: z.string().optional().default("1d"),
  from: z.string(),
  to: z.string(),
});

const AnalyticsSummarySchema = z.object({
  instrumentId: z.coerce.number().int().positive(),
  sourceId: z.coerce.number().int().positive(),
  granularity: z.string().optional().default("1d"),
  from: z.string(),
  to: z.string(),
});

const IngestStooqSchema = z.object({
  symbol: z.string().min(1),
  sourceId: z.coerce.number().int().positive(),
  instrumentId: z.coerce.number().int().positive().optional(),
});

// -------------------- Tools --------------------

// list_assets
server.tool("list_assets", "List all assets.", {}, async (...params) => {
  logParams("list_assets", params);
  const data = await httpJson(`${API_BASE}/assets`);
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// list_sources
server.tool("list_sources", "List all sources.", {}, async (...params) => {
  logParams("list_sources", params);
  const data = await httpJson(`${API_BASE}/sources`);
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// get_source
server.tool("get_source", "Get source by sourceId.", GetSourceSchema.shape, async (...params) => {
  logParams("get_source", params);

  const raw = pickArgs(params, ["sourceId"]);
  console.error("[get_source] raw picked args:", JSON.stringify(raw));

  const parsed = GetSourceSchema.safeParse(raw);
  if (!parsed.success) {
    console.error("[get_source] schema error:", parsed.error?.toString?.());
    return {
      content: [{ type: "text", text: `ERROR: invalid args for get_source: ${parsed.error}` }],
    };
  }

  const { sourceId } = parsed.data;
  const url = `${API_BASE}/sources/${sourceId}`;
  console.error("[get_source] URL:", url);

  const data = await httpJson(url);
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// get_asset
server.tool("get_asset", "Get asset details by instrumentId.", GetAssetSchema.shape, async (...params) => {
  logParams("get_asset", params);

  const raw = pickArgs(params, ["instrumentId", "sourceId", "asOf"]);
  console.error("[get_asset] raw picked args:", JSON.stringify(raw));

  const parsed = GetAssetSchema.safeParse(raw);
  if (!parsed.success) {
    console.error("[get_asset] schema error:", parsed.error?.toString?.());
    return {
      content: [{ type: "text", text: `ERROR: invalid args for get_asset: ${parsed.error}` }],
    };
  }

  const { instrumentId, sourceId, asOf } = parsed.data;

  const qs = new URLSearchParams();
  if (sourceId !== undefined) qs.set("sourceId", String(sourceId));
  if (asOf) qs.set("asOf", asOf);

  const url = `${API_BASE}/assets/${instrumentId}${qs.size ? `?${qs.toString()}` : ""}`;
  console.error("[get_asset] URL:", url);

  const data = await httpJson(url);
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// fetch_time_series
server.tool("fetch_time_series", "Fetch time series buckets.", FetchTimeSeriesSchema.shape, async (...params) => {
  logParams("fetch_time_series", params);

  const raw = pickArgs(params, ["instrumentId", "sourceId", "granularity", "from", "to"]);
  console.error("[fetch_time_series] raw picked args:", JSON.stringify(raw));

  const parsed = FetchTimeSeriesSchema.safeParse(raw);
  if (!parsed.success) {
    console.error("[fetch_time_series] schema error:", parsed.error?.toString?.());
    return {
      content: [{ type: "text", text: `ERROR: invalid args for fetch_time_series: ${parsed.error}` }],
    };
  }

  const { instrumentId, sourceId, granularity, from, to } = parsed.data;

  const qs = new URLSearchParams({
    instrumentId: String(instrumentId),
    sourceId: String(sourceId),
    granularity: String(granularity),
    from: String(from),
    to: String(to),
  });

  const url = `${API_BASE}/timeseries?${qs.toString()}`;
  console.error("[fetch_time_series] URL:", url);

  const data = await httpJson(url);
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// analytics_summary
server.tool("analytics_summary", "Compute analytics summary.", AnalyticsSummarySchema.shape, async (...params) => {
  logParams("analytics_summary", params);

  const raw = pickArgs(params, ["instrumentId", "sourceId", "granularity", "from", "to"]);
  console.error("[analytics_summary] raw picked args:", JSON.stringify(raw));

  const parsed = AnalyticsSummarySchema.safeParse(raw);
  if (!parsed.success) {
    console.error("[analytics_summary] schema error:", parsed.error?.toString?.());
    return {
      content: [{ type: "text", text: `ERROR: invalid args for analytics_summary: ${parsed.error}` }],
    };
  }

  const { instrumentId, sourceId, granularity, from, to } = parsed.data;

  const qs = new URLSearchParams({
    instrumentId: String(instrumentId),
    sourceId: String(sourceId),
    granularity: String(granularity),
    from: String(from),
    to: String(to),
  });

  const url = `${API_BASE}/analytics/summary?${qs.toString()}`;
  console.error("[analytics_summary] URL:", url);

  const data = await httpJson(url);
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// ingest_stooq_daily
server.tool("ingest_stooq_daily", "Ingest daily OHLCV from Stooq.", IngestStooqSchema.shape, async (...params) => {
  logParams("ingest_stooq_daily", params);

  const raw = pickArgs(params, ["symbol", "sourceId", "instrumentId"]);
  console.error("[ingest_stooq_daily] raw picked args:", JSON.stringify(raw));

  const parsed = IngestStooqSchema.safeParse(raw);
  if (!parsed.success) {
    console.error("[ingest_stooq_daily] schema error:", parsed.error?.toString?.());
    return {
      content: [{ type: "text", text: `ERROR: invalid args for ingest_stooq_daily: ${parsed.error}` }],
    };
  }

  const { symbol, sourceId, instrumentId } = parsed.data;

  const qs = new URLSearchParams({
    symbol: String(symbol),
    sourceId: String(sourceId),
  });
  if (instrumentId !== undefined) qs.set("instrumentId", String(instrumentId));

  const url = `${API_BASE}/ingest/stooq/daily?${qs.toString()}`;
  console.error("[ingest_stooq_daily] URL:", url);

  const data = await httpJson(url, { method: "POST" });
  return { content: [{ type: "text", text: JSON.stringify(data, null, 2) }] };
});

// -------------------- Start transport --------------------

const transport = new StdioServerTransport();
await server.connect(transport);