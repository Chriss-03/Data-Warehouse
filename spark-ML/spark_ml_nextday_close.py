import json
import sys
import requests

from pyspark.sql import SparkSession
from pyspark.sql import functions as F
from pyspark.sql import types as T

from pyspark.ml.feature import VectorAssembler
from pyspark.ml.regression import LinearRegression
from pyspark.ml.evaluation import RegressionEvaluator

"""
Spark ML workflow (mandatory remediation):
- Reads bucketed time series from REST API (/timeseries)
- Explodes points into rows (ts, open, high, low, close, volume)
- Creates supervised dataset to predict NEXT-DAY close
- Trains Spark MLlib Linear Regression
- Evaluates RMSE, prints sample predictions
- Writes metrics and predictions to output/
"""

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
    r = requests.get(url, params=params, timeout=120)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code} from {r.url}: {r.text}")
    return r.json()

def main():
    if len(sys.argv) < 5:
        print("Usage:")
        print("  spark-submit spark_ml_nextday_close.py <instrumentId> <sourceId> <from> <to> [granularity=1d] [apiBase=http://localhost:8080]")
        print("Example (AAPL.US Stooq):")
        print("  spark-submit spark_ml_nextday_close.py 504815344 20 2020-01-01T00:00:00Z 2030-01-01T00:00:00Z 1d http://localhost:8080")
        sys.exit(1)

    instrument_id = int(sys.argv[1])
    source_id = int(sys.argv[2])
    start = sys.argv[3]
    end = sys.argv[4]
    granularity = sys.argv[5] if len(sys.argv) >= 6 else "1d"
    api_base = sys.argv[6] if len(sys.argv) >= 7 else API_BASE_DEFAULT

    payload = fetch_timeseries(api_base, instrument_id, source_id, granularity, start, end)
    series = payload.get("series", {})
    buckets = payload.get("buckets", [])
    if not buckets:
        raise RuntimeError("No buckets returned. Check instrumentId/sourceId/granularity/from/to.")

    spark = SparkSession.builder.appName("DWH_SparkML_NextDayClose").getOrCreate()

    # Buckets schema
    bucket_schema = T.StructType([
        T.StructField("bucketStart", T.StringType(), True),
        T.StructField("points", T.StringType(), True),
    ])
    df_buckets = spark.createDataFrame(buckets, schema=bucket_schema)

    # points schema: array of {ts, values:{open,high,low,close,volume,...}}
    values_schema = T.MapType(T.StringType(), T.DoubleType(), True)
    point_schema = T.ArrayType(
        T.StructType([
            T.StructField("ts", T.StringType(), True),
            T.StructField("values", values_schema, True),
        ])
    )

    df = (
        df_buckets
        .withColumn("pointsArr", F.from_json(F.col("points"), point_schema))
        .withColumn("pt", F.explode(F.col("pointsArr")))
        .select(
            F.col("pt.ts").alias("ts"),
            F.col("pt.values").alias("values")
        )
        .withColumn("open", F.col("values").getItem("open"))
        .withColumn("high", F.col("values").getItem("high"))
        .withColumn("low", F.col("values").getItem("low"))
        .withColumn("close", F.col("values").getItem("close"))
        .withColumn("volume", F.col("values").getItem("volume"))
        .drop("values")
        .filter(F.col("ts").isNotNull())
    )

    # Parse ts to timestamp (ISO Z -> +00:00)
    df = df.withColumn("ts_parsed", F.to_timestamp(F.regexp_replace(F.col("ts"), "Z$", "+00:00")))

    # Ensure numeric columns exist
    df = df.select("ts_parsed", "open", "high", "low", "close", "volume").dropna(subset=["ts_parsed", "close"])

    # Create label = next day's close using a lead window over time
    w = F.window("ts_parsed", "100 years")  # not used; placeholder to avoid confusion

    from pyspark.sql.window import Window
    win = Window.orderBy(F.col("ts_parsed").asc())

    df = df.withColumn("next_close", F.lead("close", 1).over(win))

    # Remove last row without next_close
    df = df.dropna(subset=["next_close"])

    # Fill missing volume with 0.0 (some sources can have null volume)
    df = df.withColumn("volume", F.when(F.col("volume").isNull(), F.lit(0.0)).otherwise(F.col("volume")))

    # Features: today's OHLCV -> predict next day's close
    feature_cols = ["open", "high", "low", "close", "volume"]
    assembler = VectorAssembler(inputCols=feature_cols, outputCol="features")
    data = assembler.transform(df).select("ts_parsed", "features", F.col("next_close").alias("label"))

    # Train/test split
    train, test = data.randomSplit([0.8, 0.2], seed=42)

    # Spark ML model: Linear Regression
    lr = LinearRegression(featuresCol="features", labelCol="label", predictionCol="prediction")
    model = lr.fit(train)

    preds = model.transform(test)

    evaluator = RegressionEvaluator(labelCol="label", predictionCol="prediction", metricName="rmse")
    rmse = evaluator.evaluate(preds)

    evaluator_r2 = RegressionEvaluator(labelCol="label", predictionCol="prediction", metricName="r2")
    r2 = evaluator_r2.evaluate(preds)

    print("\n=== Spark ML Workflow: Next-day Close Prediction (Linear Regression) ===")
    print(f"InstrumentId: {instrument_id} | SourceId: {source_id} | Granularity: {granularity}")
    print(f"SeriesId: {series.get('seriesId')} | IndicatorSet: {series.get('indicatorSet')}")
    print(f"Train rows: {train.count()} | Test rows: {test.count()}")
    print(f"RMSE: {rmse}")
    print(f"R2: {r2}")
    print("Coefficients:", model.coefficients)
    print("Intercept:", model.intercept)

    # Show a few predictions
    print("\nSample predictions (ts, label=next_close, prediction):")
    preds.select(
        F.date_format("ts_parsed", "yyyy-MM-dd").alias("date"),
        F.col("label").alias("next_close_actual"),
        F.col("prediction").alias("next_close_predicted")
    ).orderBy(F.col("date").desc()).show(10, truncate=False)

    # Write outputs for evidence
    out_metrics = [{
        "instrumentId": instrument_id,
        "sourceId": source_id,
        "granularity": granularity,
        "seriesId": series.get("seriesId"),
        "indicatorSet": series.get("indicatorSet"),
        "trainRows": train.count(),
        "testRows": test.count(),
        "rmse": float(rmse),
        "r2": float(r2),
        "coefficients": [float(x) for x in model.coefficients],
        "intercept": float(model.intercept),
    }]

    metrics_schema = T.StructType([
        T.StructField("instrumentId", T.IntegerType(), False),
        T.StructField("sourceId", T.IntegerType(), False),
        T.StructField("granularity", T.StringType(), False),
        T.StructField("seriesId", T.LongType(), True),
        T.StructField("indicatorSet", T.StringType(), True),
        T.StructField("trainRows", T.LongType(), False),
        T.StructField("testRows", T.LongType(), False),
        T.StructField("rmse", T.DoubleType(), False),
        T.StructField("r2", T.DoubleType(), False),
        T.StructField("coefficients", T.ArrayType(T.DoubleType()), False),
        T.StructField("intercept", T.DoubleType(), False),
    ])

    spark.createDataFrame(out_metrics, schema=metrics_schema).coalesce(1).write.mode("overwrite").json("output/metrics")

    preds_out = preds.select(
        F.col("ts_parsed"),
        F.col("label").alias("next_close_actual"),
        F.col("prediction").alias("next_close_predicted")
    )

    preds_out.coalesce(1).write.mode("overwrite").option("header", True).csv("output/predictions")

    print("\nWrote outputs to:")
    print("  spark-ML/output/metrics (JSON)")
    print("  spark-ML/output/predictions (CSV)")
    spark.stop()

if __name__ == "__main__":
    main()