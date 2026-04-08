-- ============================================================
-- SpiceMoney — Athena Setup for Compliance Log Queries
-- Run once to set up table pointing to S3 compliance bucket
-- ============================================================

-- Step 1: Create database
CREATE DATABASE IF NOT EXISTS spicemoney_logs;

-- Step 2: Create external table pointing to S3
CREATE EXTERNAL TABLE IF NOT EXISTS spicemoney_logs.app_logs (
  timestamp     string,
  severity_text string,
  body          string,
  resource      struct<
    `k8s.namespace.name`: string,
    `k8s.pod.name`:       string,
    `k8s.container.name`: string
  >,
  trace_id      string,
  span_id       string
)
PARTITIONED BY (year string, month string, day string, hour string)
ROW FORMAT SERDE 'org.apache.hive.hcatalog.data.JsonSerDe'
STORED AS TEXTFILE
LOCATION 's3://spicemoney-logs-compliance/logs/'
TBLPROPERTIES ('has_encrypted_data'='false');

-- Step 3: Load partitions (re-run after new data arrives)
MSCK REPAIR TABLE spicemoney_logs.app_logs;


-- ============================================================
-- Sample Compliance Audit Queries
-- ============================================================

-- All ERROR logs for a specific day
SELECT timestamp, severity_text, body,
       resource.`k8s.namespace.name` AS namespace,
       resource.`k8s.pod.name`       AS pod
FROM   spicemoney_logs.app_logs
WHERE  year='2025' AND month='01' AND day='15'
  AND  severity_text = 'ERROR'
ORDER BY timestamp DESC
LIMIT 100;

-- Logs for a specific service on a specific day
SELECT timestamp, severity_text, body
FROM   spicemoney_logs.app_logs
WHERE  year='2025' AND month='01' AND day='15'
  AND  resource.`k8s.namespace.name` = 'payments'
ORDER BY timestamp DESC;

-- Trace correlation — find all logs for a specific trace
SELECT timestamp, severity_text, body,
       resource.`k8s.pod.name` AS pod
FROM   spicemoney_logs.app_logs
WHERE  year='2025' AND month='01' AND day='15'
  AND  trace_id = 'your-trace-id-here'
ORDER BY timestamp ASC;

-- Error count per service per day
SELECT resource.`k8s.namespace.name` AS namespace,
       COUNT(*) AS error_count
FROM   spicemoney_logs.app_logs
WHERE  year='2025' AND month='01'
  AND  severity_text = 'ERROR'
GROUP BY resource.`k8s.namespace.name`
ORDER BY error_count DESC;
