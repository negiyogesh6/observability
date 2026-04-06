# Building a Production-Grade Observability Platform on EKS Using 100% Open Source Tools

## The Problem — Flying Blind in Production

Modern applications running on Kubernetes are distributed by nature. A single user request can touch multiple services — a Java Spring Boot API, a Python payment processor, a Node.js frontend — each running as multiple pods across multiple nodes on AWS EKS.

When something goes wrong at 2 AM, the questions are always the same:

- Which service is down?
- Is it a code bug, a resource issue, or an infrastructure failure?
- Which pod? Which node?
- When did it start?
- Is it affecting all users or just some?

Without observability, answering these questions means SSH-ing into nodes, grepping logs, and guessing. With observability, you have the answers before the alert even fires.

This is the story of how we built a complete observability platform for a microservices application running on AWS EKS — using only open source tools, zero vendor lock-in, and zero per-seat licensing cost.

---

## What is Observability?

Observability is the ability to understand the internal state of a system by examining its external outputs. In practice it means collecting and correlating three types of data:

```
+--------------------+       +--------------------+       +--------------------+
|   📊  METRICS      |       |    📝  LOGS         |       |   🔍  TRACES       |
|--------------------|       |--------------------|       |--------------------|
|  What happened?    |       |  Why it happened?  |       | Where it happened? |
|                    | <---> |                    | <---> |                    |
|  CPU · Latency     |       |  Stack traces      |       |  A -> B -> C -> DB |
|  Errors · Req/s    |       |  DB errors         |       |  Total = 2.3s      |
+--------------------+       +--------------------+       +--------------------+
         ^                                                          |
         +----------------------------------------------------------+
                           Correlate all three
```

The real power comes when you can correlate all three — see a latency spike in metrics, jump to the trace that caused it, and read the exact log line that explains why.

---

## Why Open Source?

We evaluated Datadog, New Relic, and Dynatrace. We chose open source for three reasons:

1. **Cost** — No per-host or per-seat pricing. Commercial APM tools cost $50–200 per host per month at scale.
2. **Control** — Data stays in our AWS account. Nothing leaves our VPC.
3. **Flexibility** — We can extend, modify, and integrate anything without waiting for a vendor.

### The Tools We Chose

| Tool | Role | Why |
|---|---|---|
| **OpenTelemetry** | Instrumentation & collection | CNCF standard, vendor-neutral, auto-instrumentation |
| **Prometheus** | Metrics storage & alerting | Industry standard, powerful PromQL |
| **Grafana** | Visualization | Best-in-class dashboards, supports all data sources |
| **Tempo** | Distributed tracing | Grafana-native, cost-efficient trace storage |
| **Loki** | Log aggregation | Prometheus-like log queries, low storage cost |
| **Blackbox Exporter** | API health probing | Active endpoint monitoring, SSL cert tracking |
| **kube-state-metrics** | Kubernetes state metrics | Deployment, pod, node state as Prometheus metrics |
| **node-exporter** | Node-level metrics | CPU, memory, disk, network per EC2 node |

---

## Architecture — How It All Fits Together

```
  +-------------------+    +-------------------+    +-------------------+
  |  ☕  Java Service  |    |  🐍  Python Svc   |    |  🟩  Node.js Svc  |
  |      :8080        |    |      :5000        |    |      :3000        |
  |   [OTel Agent]    |    |   [OTel Agent]    |    |   [OTel Agent]    |
  +--------+----------+    +--------+----------+    +--------+----------+
           |                        |                        |
           +------------------------+------------------------+
                                    |
                              OTLP HTTP :4318
                                    |
                                    v
  +-----------------------------------------------------------------------+
  |           🔄  OpenTelemetry Collector  (DaemonSet)                    |
  |-----------------------------------------------------------------------|
  |  📥 Receive          |  ⚙️  Process       |  🔗 spanmetrics           |
  |  OTLP gRPC  :4317   |  batch            |  traces -> RED metrics      |
  |  OTLP HTTP  :4318   |  memory_limiter   |                             |
  |  FileLog            |                   |  📤 Export to backends      |
  +----------+----------+-------------------+------------------------------+
             |                    |                        |
         🔍 Traces           📊 Metrics               📝 Logs
             |                    |                        |
             v                    v                        v
  +------------------+  +--------------------+  +------------------+
  |    🔍  Tempo     |  |   📊  Prometheus   |  |    📝  Loki      |
  |------------------|  |--------------------|  |------------------|
  |  Distributed     |  |  Metrics + Alerts  |  |  Log Storage     |
  |  Trace Storage   |  |  PromQL Engine     |  |  LogQL Engine    |
  |  50Gi            |  |  50Gi              |  |  50Gi            |
  +------------------+  +---------+----------+  +------------------+
                                   |
                        📡  Also scrapes:
                        |-- 🖥️   node-exporter      (DaemonSet)
                        |-- ☸️   kube-state-metrics
                        |-- 📦  cAdvisor             (kubelet built-in)
                        +-- 🔎  blackbox-exporter    (API health probes)
                                   |
                                   v
  +----------------------------------------------------------------------+
  |                         📈  Grafana                                  |
  |----------------------------------------------------------------------|
  |  +------------------+  +------------------+  +------------------+    |
  |  | 📊  APM          |  | 🖥️   EKS         |  | ☕  JVM           |    |
  |  | Golden Signals   |  | NOC · Nodes      |  | Heap · GC        |    |
  |  | Apdex · Latency  |  | Pods · Security  |  | Threads · CPU    |    |
  |  +------------------+  +------------------+  +------------------+    |
  |                         +------------------+                         |
  |                         | 💰  Cost         |                         |
  |                         | Efficiency       |                         |
  |                         | Rightsizing      |                         |
  |                         +------------------+                         |
  +----------------------------------------------------------------------+
```

---

## Step 1 — Zero-Code Instrumentation with OpenTelemetry

The biggest challenge with observability is getting developers to instrument their code. We solved this with the **OpenTelemetry Operator** — a Kubernetes operator that injects instrumentation automatically.

### How It Works

Add a single annotation to your pod spec:

```yaml
annotations:
  instrumentation.opentelemetry.io/inject-java: "monitoring/java-instrumentation"
```

The operator automatically:
1. Injects the OTel Java agent as an init container
2. Sets all required environment variables
3. Configures the agent to send data to the OTel Collector

**Zero code changes required in the application.**

### What Gets Instrumented Automatically

**Java (Spring Boot):**
- All HTTP requests and responses (Spring MVC)
- Database queries (JDBC, Hibernate)
- External HTTP calls (RestTemplate, WebClient)
- JVM metrics — heap, GC, threads, CPU, class loading
- Messaging (Kafka, RabbitMQ)

**Python (Flask/FastAPI):**
- All HTTP endpoints
- Database queries (SQLAlchemy)
- Redis calls, external HTTP requests

**Node.js (Express):**
- All HTTP routes
- Database queries, external HTTP calls

### Instrumentation Configuration

```yaml
apiVersion: opentelemetry.io/v1alpha1
kind: Instrumentation
metadata:
  name: java-instrumentation
  namespace: monitoring
spec:
  exporter:
    endpoint: http://otel-collector.monitoring.svc.cluster.local:4318
  java:
    image: ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2.9.0
    env:
      - name: OTEL_METRICS_EXPORTER
        value: "otlp"
      - name: OTEL_EXPORTER_OTLP_PROTOCOL
        value: "http/protobuf"
      - name: OTEL_INSTRUMENTATION_JVM_ENABLED
        value: "true"
```

> **Key Learning:** The OTel Java agent uses HTTP (port 4318) by default, not gRPC (port 4317). Always set `OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf` explicitly to avoid silent connection failures.

---

## Step 2 — OTel Collector as the Central Hub

The OTel Collector runs as a **DaemonSet** — one pod per node — and acts as the central nervous system of the platform.

### SpanMetrics Connector — Turning Traces into Metrics

The most powerful feature is the `spanmetrics` connector. It automatically derives Prometheus metrics from distributed traces — no extra instrumentation needed:

```yaml
connectors:
  spanmetrics:
    namespace: traces_spanmetrics
    histogram:
      explicit:
        buckets: [10ms, 25ms, 50ms, 100ms, 200ms, 400ms, 800ms, 1s, 2s, 5s, 10s]
    dimensions:
      - name: http.method
      - name: http.status_code
      - name: db.system
```

This generates:
- `otel_traces_spanmetrics_calls_total` — request rate per service
- `otel_traces_spanmetrics_duration_milliseconds_bucket` — latency histogram for P50/P95/P99
- Used to calculate Apdex score per service

### Log Collection

The FileLog receiver reads pod logs directly from the node filesystem (`/var/log/pods`) and enriches them with Kubernetes metadata before shipping to Loki:

```yaml
receivers:
  filelog:
    include: [/var/log/pods/*/*/*.log]
    operators:
      - type: container
      - type: regex_parser
        regex: '^.*\/(?P<namespace>[^_]+)_(?P<pod_name>[^_]+)_...'
```

---

## Step 3 — Prometheus for Metrics and Alerting

Prometheus scrapes metrics from multiple sources every 15 seconds.

### Scrape Targets

| Job | Source | What It Provides |
|---|---|---|
| `kubernetes-cadvisor` | kubelet built-in | Container CPU, memory, network per pod |
| `node-exporter` | DaemonSet on each node | Node CPU, memory, disk, network |
| `kube-state-metrics` | Kubernetes API | Pod state, deployment replicas, PVC status |
| `otel-collector` | OTel Collector :8889 | APM metrics, JVM metrics, span metrics |
| `blackbox-http` | Blackbox Exporter | API health, response time, SSL expiry |

### Alert Rules — Three Groups

**Infrastructure (eks-alerts):**
- `PodCrashLooping` — pod restarting continuously for > 5 min
- `NodeNotReady` — node down or unresponsive for > 2 min
- `PVCNearFull` — storage > 85% used
- `PVCCriticalFull` — storage > 95% used
- `CPUThrottlingHigh` — container throttled > 50% for > 10 min
- `DeploymentReplicasMismatch` — desired vs ready mismatch for > 5 min

**APM (apm-alerts):**
- `HighLatencyP95` — service P95 latency > 2 seconds
- `HighErrorRate` — service error rate > 5%

**Blackbox (blackbox-alerts):**
- `ServiceDown` — health probe failing for > 1 minute
- `ServiceSlowResponse` — response time > 2 seconds for > 3 min
- `SSLCertExpiringSoon` — certificate expires in < 30 days
- `SSLCertExpiryCritical` — certificate expires in < 7 days

---

## Step 4 — Blackbox Exporter for Active API Monitoring

Unlike passive monitoring (waiting for metrics to arrive), Blackbox Exporter **actively probes** your API endpoints every 15 seconds from inside the cluster.

```
                            [ every 15 seconds ]
                            +-------------------+
                            |                   |
  +------------------+      v                   |      +--------------------+
  |  📊  Prometheus  | -- scrape /probe --> +---+---+  |  🌐  Service       |
  |                  |                      |  🔎   |  |  /health endpoint  |
  |                  | <-- metrics -------- | Black |  |                    |
  +--------+---------+                      |  box  +->|  HTTP 200 OK       |
           |                                +-------+  |  response: 45ms    |
           |                                           +--------------------+
           |
           |   probe_success             = 1 (UP) / 0 (DOWN)
           |   probe_duration_seconds    = 0.045
           |   probe_http_status_code    = 200
           |   probe_ssl_earliest_expiry = 89 days
           |   probe_dns_lookup_time     = 0.002s
           |
           |   if probe_success = 0  for more than 1 minute
           |
           v
  +------------------+
  |  🚨  Alert Fired |
  |  ServiceDown     |
  |  severity:       |
  |  critical        |
  +------------------+
```

If an app crashes completely, passive monitoring goes silent. Blackbox still fires an alert within 1 minute.

---

## Step 5 — JVM Metrics Without Code Changes

Java applications expose rich JVM internals through OTel Java agent v2.9.0+:

```
JVM Metrics Collected:
  jvm.memory.used / committed / max   — heap and non-heap
  jvm.gc.duration                     — GC pause time histogram
  jvm.gc.collections                  — GC count by type (G1, ZGC etc.)
  jvm.threads.count                   — by state: runnable, blocked, waiting
  process.cpu.usage                   — JVM process CPU %
  jvm.classes.loaded / unloaded       — class loading activity
```

These flow through OTel Collector → Prometheus → Grafana JVM dashboard, giving developers full visibility into memory pressure, GC behavior, and thread contention without any code changes.

---

## Key Technical Challenges and How We Solved Them

### Challenge 1 — Metric Name Prefix
OTel Collector's Prometheus exporter adds a namespace prefix. Metrics become `otel_traces_spanmetrics_*` not `traces_spanmetrics_*`. All dashboard queries must use the correct prefix.

### Challenge 2 — Apdex Score Calculation
Standard Apdex uses 200ms/800ms thresholds. OTel spanmetrics histogram buckets are in seconds. The correct `le` values are `le="0.256"` (satisfied ~256ms) and `le="1.024"` (tolerating ~1s) based on actual bucket boundaries.

### Challenge 3 — Node Label Mismatch Between cAdvisor and kube-state-metrics
cAdvisor uses `instance=hostname` while kube-state-metrics uses `node=hostname`. Direct PromQL joins fail silently. Solution: use `job="node-exporter"` filter and match on the `instance` label which both node-exporter and cAdvisor share consistently.

### Challenge 4 — Service Name in JVM Metrics
JVM metrics don't carry `service_name` label natively. Service names appear as `exported_job` in format `namespace/service_name`. Prometheus relabel config promotes this to the `job` label for proper filtering in Grafana variables.

### Challenge 5 — Deployment Filtering in Grafana
`container_cpu_usage_seconds_total` has `pod` label but not `deployment`. To filter by deployment we join through two metrics:
```
pod → kube_pod_info.created_by_name (replicaset name)
    → kube_replicaset_owner.owner_name (deployment name)
```
Grafana variable query:
```
label_values(kube_replicaset_owner{namespace=~"$namespace", owner_kind="Deployment"}, owner_name)
```

---

## Resource Footprint

The entire observability stack runs on existing EKS nodes:

| Component | CPU Request | Memory Request | Type |
|---|---|---|---|
| Prometheus | 500m | 1Gi | StatefulSet |
| Grafana | 100m | 128Mi | Deployment |
| Tempo | 200m | 512Mi | StatefulSet |
| Loki | 200m | 256Mi | StatefulSet |
| OTel Collector | 200m | 256Mi | DaemonSet (per node) |
| kube-state-metrics | 10m | 32Mi | Deployment |
| node-exporter | 10m | 32Mi | DaemonSet (per node) |
| Blackbox Exporter | 10m | 32Mi | Deployment |

Total overhead on a 3-node cluster: ~**1.5 CPU cores** and ~**2.5Gi memory**.

---

## Cost Comparison

| Approach | Monthly Cost (10 services, 3 nodes) |
|---|---|
| Datadog APM + Infrastructure | ~$800–1,200/month |
| New Relic Full Stack | ~$600–1,000/month |
| Dynatrace | ~$900–1,500/month |
| **Our Open Source Stack** | **$0 licensing + ~$15 EBS storage** |

---

## What We Achieved

- Zero-code instrumentation for Java, Python, and Node.js
- Full distributed tracing across all services with Tempo
- Automatic RED metrics from traces via spanmetrics connector
- JVM deep-dive metrics without any application changes
- Active API health monitoring with Blackbox Exporter
- 25+ alert rules covering infrastructure, APM, and API health
- 4 production dashboards covering APM, JVM, EKS infrastructure, and cost optimization
- Cost visibility identifying over-provisioned and idle resources
- 100% open source, zero vendor lock-in, all data stays in AWS account

---

## What's Next

- **OpenCost** — Real `$` cost allocation per namespace/deployment using AWS EC2 pricing API
- **Alertmanager** — Route alerts to Slack, PagerDuty, or email with escalation policies
- **SLO/SLA tracking** — Define and track error budgets per service
- **Continuous profiling** — Add Pyroscope for CPU/memory flame graphs
- **Multi-cluster federation** — Federate Prometheus across multiple EKS clusters

---

## Conclusion

Building observability from scratch sounds daunting but the open source ecosystem has matured to the point where you can have a production-grade platform running in a day. The key insight is that OpenTelemetry has become the standard — instrument once, export anywhere. Combined with Prometheus for metrics, Tempo for traces, Loki for logs, and Grafana for visualization, you get a platform that rivals commercial offerings at a fraction of the cost.

The investment pays off the first time you resolve a production incident in minutes instead of hours.
