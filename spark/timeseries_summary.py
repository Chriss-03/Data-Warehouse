import json
import sys
import requests
from datetime import datetime

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql import types as T

API_BASE_DEFAULT = "http://localhost:8080"

def fetch_timeseries(api_base: str, instrument_id: int, source_id: int, granularity: str, start: str, end: str):
    url = f"{api_base}/timeseries"
    params = {
        "instrumentId": instrument_id,
        "sourceId": source_id,
        "granularity": granularity,
        "from": start,
        "to": end,
    }
    r = requests.get(url, params=params, timeout=60)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code} from {r.url}: {r.text}")
    return r.json()

def main():
    if len(sys.argv) < 6:
        print("Usage:")
        print("  spark-submit timeseries_summary.py <instrumentId> <sourceId> <from> <to> <granularity(optional, default=1d)> [apiBase(optional)]")
        print("Example:")
        print("  spark-submit timeseries_summary.py 1001 10 2025-10-01T00:00:00Z 2027-04-01T00:00:00Z 1d http://localhost:8080")
        sys.exit(1)

    instrument_id = int(sys.argv[1])
    source_id = int(sys.argv[2])
    start = sys.argv[3]
    end = sys.argv[4]
    granularity = sys.argv[5] if len(sys.argv) >= 6 else "1d"
    api_base = sys.argv[6] if len(sys.argv) >= 7 else API_BASE_DEFAULT

    # 1) Fetch from REST API
    payload = fetch_timeseries(api_base, instrument_id, source_id, granularity, start, end)

    series = payload.get("series", {})
    buckets = payload.get("buckets", [])
    if not buckets:
        raise RuntimeError("No buckets returned. Check instrumentId/sourceId/granularity/from/to.")

    # 2) Build Spark
    spark = SparkSession.builder.appName("DWH_TimeSeries_Summary").getOrCreate()

    bucket_schema = T.StructType([
        T.StructField("bucketStart", T.StringType(), True),
        T.StructField("points", T.StringType(), True),  # JSON string array
    ])

    df_buckets = spark.createDataFrame(buckets, schema=bucket_schema)

    values_schema = T.MapType(T.StringType(), T.DoubleType(), True)
    point_schema = T.ArrayType(
        T.StructType([
            T.StructField("ts", T.StringType(), True),
            T.StructField("values", values_schema, True),
        ])
    )

    df_points = (
        df_buckets
        .withColumn("pointsArr", F.from_json(F.col("points"), point_schema))
        .withColumn("pt", F.explode(F.col("pointsArr")))
        .select(
            F.col("pt.ts").alias("ts"),
            F.col("pt.values").alias("values")
        )
        .withColumn("close", F.col("values").getItem("close"))
        .withColumn("volume", F.col("values").getItem("volume"))
        .drop("values")
        .filter(F.col("ts").isNotNull())
    )

    df_points = df_points.withColumn(
        "ts_parsed",
        F.to_timestamp(F.regexp_replace(F.col("ts"), "Z$", "+00:00"))
    )

    agg = df_points.agg(
        F.count("*").alias("pointsCount"),
        F.min("close").alias("minClose"),
        F.max("close").alias("maxClose"),
        F.avg("close").alias("avgClose"),
        F.sum("volume").alias("totalVolume"),
        F.min("ts_parsed").alias("firstTs"),
        F.max("ts_parsed").alias("lastTs"),
    )

    w_asc = F.window("ts_parsed", "100 years")
    first_row = df_points.orderBy(F.col("ts_parsed").asc()).select("ts", "close").limit(1).collect()[0]
    last_row = df_points.orderBy(F.col("ts_parsed").desc()).select("ts", "close").limit(1).collect()[0]

    first_ts = first_row["ts"]
    first_close = first_row["close"]
    last_ts = last_row["ts"]
    last_close = last_row["close"]

    simple_return = None
    if first_close is not None and last_close is not None and first_close != 0:
        simple_return = float((last_close - first_close) / first_close)

    agg_row = agg.collect()[0].asDict()

    result = {
        "instrumentId": instrument_id,
        "sourceId": source_id,
        "granularity": granularity,
        "from": start,
        "to": end,
        "seriesId": series.get("seriesId"),
        "indicatorSet": series.get("indicatorSet"),
        "pointsCount": int(agg_row["pointsCount"]),
        "minClose": float(agg_row["minClose"]) if agg_row["minClose"] is not None else None,
        "maxClose": float(agg_row["maxClose"]) if agg_row["maxClose"] is not None else None,
        "avgClose": float(agg_row["avgClose"]) if agg_row["avgClose"] is not None else None,
        "totalVolume": float(agg_row["totalVolume"]) if agg_row["totalVolume"] is not None else None,
        "firstTs": first_ts,
        "firstClose": float(first_close) if first_close is not None else None,
        "lastTs": last_ts,
        "lastClose": float(last_close) if last_close is not None else None,
        "simpleReturn": simple_return,
    }

    print("\n=== Spark Summary Result (computed from /timeseries) ===")
    print(json.dumps(result, indent=2))

    out_schema = T.StructType([
        T.StructField("instrumentId", T.IntegerType(), False),
        T.StructField("sourceId", T.IntegerType(), False),
        T.StructField("granularity", T.StringType(), False),
        T.StructField("from", T.StringType(), False),
        T.StructField("to", T.StringType(), False),
        T.StructField("seriesId", T.LongType(), True),
        T.StructField("indicatorSet", T.StringType(), True),
        T.StructField("pointsCount", T.IntegerType(), True),
        T.StructField("minClose", T.DoubleType(), True),
        T.StructField("maxClose", T.DoubleType(), True),
        T.StructField("avgClose", T.DoubleType(), True),
        T.StructField("totalVolume", T.DoubleType(), True),
        T.StructField("firstTs", T.StringType(), True),
        T.StructField("firstClose", T.DoubleType(), True),
        T.StructField("lastTs", T.StringType(), True),
        T.StructField("lastClose", T.DoubleType(), True),
        T.StructField("simpleReturn", T.DoubleType(), True),
    ])

    df_out = spark.createDataFrame([result], schema=out_schema)
    df_out.coalesce(1).write.mode("overwrite").option("header", True).csv("output")

    print("\nWrote CSV to: spark_job/output/ (one partition)")
    spark.stop()

if __name__ == "__main__":
    main()