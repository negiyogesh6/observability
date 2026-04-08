# Application Log Management Strategy
## Proposal for SpiceMoney Observability Platform
**Prepared by:** Platform Engineering Team
**Platform:** AWS EKS (Kubernetes)
**Deployment Type:** Fresh Setup
**Date:** 2025

---

## Context

SpiceMoney is deploying 15+ microservices (Java, Python, Node.js) on AWS EKS with
Horizontal Pod Autoscaling (HPA). The observability stack includes OpenTelemetry,
Grafana, Loki, Tempo, and Prometheus.

This document evaluates all viable approaches for application log management and
recommends the most suitable solution for production, covering:

- Log collection without loss
- Long-term compliance retention
- Independent log access without tool dependency
- HPA and auto-scaling compatibility
- Zero or minimal application code changes

---

## Understanding the Problem

In a containerised environment on Kubernetes, application logs follow this path:

```
Application writes a log line (stdout)
          ↓
Kubelet captures and writes to node filesystem
          ↓
/var/log/pods/<namespace>/<pod>/<container>/0.log
          ↓
Log collector reads and ships to storage
          ↓
Hot storage (recent queries) + Cold storage (compliance, years)
```

The core challenges are:

- What happens if the log collector crashes mid-send?
- What happens if the node running the collector is terminated?
- How do we store logs for years without being locked into a single tool?
- How do we handle 15+ APIs with HPA scaling pods up and down dynamically?

---

## Solutions Evaluated

### Solution 1 — EBS Persistent Volume (PVC) per Pod

Each application pod is given a dedicated AWS EBS volume to write log files.

```
App Pod → writes logs → EBS Volume (/app/logs/)
                              ↓
                    Sidecar reads and ships
                              ↓
                         Loki / S3
```

**Pros**
- Logs survive pod crash — file is on EBS, not lost with the pod
- Logs survive graceful node termination — EBS detaches and reattaches to new node

**Cons**
- EBS is ReadWriteOnce — only one pod can mount at a time, collector cannot share it
- EBS is Availability Zone locked — if node fails and pod reschedules to a different AZ,
  EBS cannot attach, pod stays in Pending state for up to 6 minutes
- HPA incompatible — every new pod needs a new EBS volume (30–60 sec provisioning delay),
  defeating the purpose of auto-scaling during traffic spikes
- At peak scale (15 APIs × 10 pods = 150 pods), 150 EBS volumes needed simultaneously
- Requires a sidecar container per pod to read and forward logs — 15 APIs = 15 sidecar
  configurations to maintain and monitor
- Orphaned EBS volumes accumulate when HPA scales down — requires a cleanup job
- Still requires S3 for long-term retention — paying for EBS and S3 for the same data
- Estimated cost: 150 pods × 20 GB × $0.08/GB = $240/month for EBS alone

**Verdict:** Not suitable for HPA-based microservices.

---

### Solution 2 — Amazon EFS Shared Volume

All pods mount a shared EFS (network filesystem) volume. Collector reads from EFS.

```
All App Pods → write logs → EFS shared mount (/efs/logs/)
                                    ↓
                          Collector reads EFS
                                    ↓
                               Loki / S3
```

**Pros**
- ReadWriteMany — multiple pods can mount simultaneously
- Multi-AZ — no availability zone lock
- HPA compatible — no new volume provisioned per pod

**Cons**
- EFS is a network filesystem — every log write is a network call.
  Local disk write latency is 0.1ms, EFS write latency is 3–10ms.
  For high-throughput payment APIs this directly impacts application response time
- File conflicts — multiple pods of the same service writing to EFS must each use a
  unique filename (pod name or UID), which requires a configuration change per pod
- Orphaned log files remain on EFS after pods are terminated — requires a cleanup job
- Still requires a collector to read EFS and ship to Loki and S3 — EFS is just an
  expensive middle layer that adds no real durability benefit
- Still requires S3 for long-term retention — paying for both EFS and S3
- Estimated cost: 15 APIs × 10 pods × 1 GB/day × 30 days = 4,500 GB/month
  × $0.30/GB = $1,350/month vs $103/month with the recommended solution (13x more expensive)

**Verdict:** Solves the multi-pod problem but introduces write latency, file conflicts,
and 13x higher cost. Not suitable.

---

### Solution 3 — Log File Inside Container Filesystem

Application writes logs to a file path inside the container (e.g. /app/logs/app.log).

```
App → /app/logs/app.log (ephemeral container filesystem)
           ↓
      Invisible to node-level collector
           ↓
      Requires volume mount + sidecar per pod
```

**Pros**
- Structured log files per application
- Not subject to kubelet log rotation limits

**Cons**
- Container filesystem is ephemeral — all log files are lost on pod restart
- Node-level collector cannot see files inside a container's own filesystem
- Requires a volume mount and sidecar per pod to tail the file and write to stdout
- Requires a deployment manifest change for every one of the 15+ APIs
- Pod restart means total log loss — worst durability of all options

**Verdict:** Not suitable. Provides no durability benefit and requires the most changes.

---

### Solution 4 — Kafka as Intermediate Buffer

Add Apache Kafka between the log collector and storage backends (Loki, S3).

```
App stdout → Collector → Kafka (3 brokers) → Consumer → Loki + S3
```

**Pros**
- Durable buffer — logs safe in Kafka even if Loki or S3 is temporarily unavailable
- Replayable — if a downstream system was down, logs can be re-consumed from Kafka topic
- Handles very high throughput (50,000+ log lines per second)
- Multiple teams can consume the same log stream independently

**Cons**
- Kafka does NOT solve node termination log loss — logs must reach Kafka first,
  the same risk window between node filesystem and Kafka still exists
- Requires 3 Kafka broker pods with StatefulSets, persistent volumes, and capacity planning
- Requires a separate consumer (e.g. Vector) to read from Kafka and write to Loki and S3,
  adding two more components to operate and monitor
- Significantly increases operational complexity for a problem solvable with a simple
  persistent queue in the existing collector
- Only justified when log volume consistently exceeds 50,000 lines per second or when
  multiple independent teams need to consume the same log stream

**Verdict:** Overkill for current scale. The same downstream resilience can be achieved
with the collector's built-in persistent queue. Revisit if log volume exceeds 50,000
lines per second.

---

### Solution 5 — stdout + OTel Collector DaemonSet + S3 (Recommended)

Applications write to stdout (Kubernetes default). The OTel Collector DaemonSet already
deployed on every node reads logs, ships to Loki for hot queries, and simultaneously
exports raw logs to S3 for long-term compliance storage.

```
Application (stdout — no code change)
          ↓
/var/log/pods/ — kubelet managed, auto-rotated
          ↓
OTel Collector DaemonSet (one per node, already in stack)
  ├── filelog receiver with checkpoint    → no missed logs on restart
  ├── disk-backed persistent queue        → no loss if Loki or S3 down
  ├── Flush every 1 second               → minimises buffer window
  ├── Retry forever                       → never drops logs
  └── terminationGracePeriodSeconds 60   → flushes on graceful shutdown
          ↓                                        ↓
        Loki (S3 backend)                        S3 raw gzip JSON
        30 days hot storage                      7 years cold storage
        Grafana / LogQL queries                  Athena SQL queries
                                                 (no Loki dependency)
```

The same OTel Collector DaemonSet also handles traces and metrics from the
auto-instrumentation agent — one agent, all three signals, no additional components.

**Pros**
- Zero application code changes — all 15+ APIs collected automatically from day one
- HPA fully compatible — new pods collected instantly, no provisioning delay
- Zero additional infrastructure cost for collection — node filesystem is free
- Kubelet handles log rotation automatically
- Checkpoint file ensures no logs are missed when collector restarts
- Independent disk-backed queue per output — Loki down does not affect S3 export
  and S3 throttle does not affect Loki ingestion
- S3 raw JSON is completely independent of Loki — logs are queryable via Athena
  even if Loki is unavailable, corrupted, or decommissioned in the future
- One DaemonSet handles logs, traces, and metrics — no extra components to operate
- Graceful node termination (Karpenter scale-in, Spot 2-minute warning) triggers
  SIGTERM, collector flushes remaining buffer before exit — no loss
- S3 lifecycle tiering reduces long-term storage cost to near zero over time
- Estimated cost: ~$103/month for 150 GB/month log volume at full scale

**Cons**
- Hard node failure (kernel panic, EC2 hardware failure) — maximum 1 second of logs
  lost with Flush=1 configuration. This is unavoidable in any log collection
  architecture including Kafka, EFS, and EBS. Accepted by all major compliance
  standards including PCI-DSS, SOC2, and RBI IT Framework guidelines

**Verdict:** Recommended. Kubernetes-native, production proven, meets compliance
requirements, zero application changes required.

---

## Solutions Comparison

| Criteria | EBS PVC | EFS | File in Container | Kafka | Recommended |
|----------|:-------:|:---:|:-----------------:|:-----:|:-----------:|
| Zero app changes required | ✓ | ✗ | ✗ | ✓ | ✓ |
| HPA / auto-scaling compatible | ✗ | ✓ | ✗ | ✓ | ✓ |
| Pod crash safe | ✓ | ✓ | ✗ | ✓ | ✓ |
| Graceful node termination safe | ✓ | ✓ | ✗ | ✓ | ✓ |
| Hard node failure | Worse (AZ lock) | ✓ | ✗ | Same gap | Max 1 sec |
| Loki down — no log loss | Depends | Depends | ✗ | ✓ | ✓ |
| Independent query (no Loki) | ✗ | ✗ | ✗ | ✓ | ✓ |
| Long-term compliance storage | ✗ | ✗ | ✗ | ✓ | ✓ |
| Extra components needed | Sidecar per pod | Sidecar + EFS | Sidecar per pod | Kafka + Vector | None |
| Operational complexity | High | High | High | Very High | Low |
| Estimated monthly cost | $240+ | $1,350+ | Low | Medium | ~$103 |
| Production ready | ✗ | ✗ | ✗ | ✓ (overkill) | ✓ |

---

## Recommended Solution — What Needs to Be Done

### Infrastructure Setup (Platform Team)

| # | Action | Notes |
|---|--------|-------|
| 1 | Create S3 bucket: spicemoney-loki-chunks | Loki hot storage backend |
| 2 | Create S3 bucket: spicemoney-logs-compliance | Raw log archive, 7-year retention |
| 3 | Apply S3 lifecycle policy to compliance bucket | STANDARD → IA → GLACIER → DEEP_ARCHIVE |
| 4 | Configure IRSA for otel-collector service account | S3 write permission |
| 5 | Configure IRSA for loki service account | S3 read/write permission |
| 6 | Deploy updated OTel Collector config | Persistent queue + S3 exporter added |
| 7 | Deploy updated Loki config | S3 backend + 30-day retention |
| 8 | Deploy updated Karpenter nodeclass | Kubelet log rotation tuned |
| 9 | Set up Athena table | One-time setup for compliance queries |

### Application Team (Recommended — Not Mandatory for Core Solution)

| # | Action | Effort | Value |
|---|--------|--------|-------|
| 1 | Enable structured JSON logging | 1 day per service | High — enables field-level queries in Grafana and Athena |
| 2 | Add trace ID to log output | 2 hours per service | High — enables log to trace navigation in Grafana |
| 3 | Set production log level to WARN | 30 min per service | Medium — reduces log volume and cost |
| 4 | Implement sensitive data masking | 1–2 days | Critical — compliance with RBI data protection guidelines |

### Structured JSON Logging Example (Spring Boot — One Dependency)

```xml
<!-- pom.xml — one dependency addition -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>
```

```xml
<!-- logback-spring.xml — one configuration file -->
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
</appender>
```

Log output becomes:
```json
{
  "timestamp": "2025-01-15T10:23:45.123Z",
  "level": "ERROR",
  "service": "payment-service",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "message": "Payment failed",
  "order_id": "12345"
}
```

This enables queries like "show all errors for order 12345 across all services" directly
in Grafana or Athena — not possible with plain text logs.

---

## Log Retention and Cost

| Storage Tier | Duration | Cost/GB/month | Purpose |
|-------------|----------|---------------|---------|
| Loki on S3 | 30 days | $0.023 | Recent logs, Grafana dashboards, alerting |
| S3 STANDARD | 0–90 days | $0.023 | Compliance copy, fast access |
| S3 STANDARD_IA | 90–365 days | $0.0125 | Infrequent compliance access |
| S3 GLACIER | 1–7 years | $0.004 | Long-term archive |
| S3 DEEP_ARCHIVE | 7 years | $0.00099 | Maximum retention, lowest cost |

Estimated total cost at 150 GB/month log volume (15 APIs at full HPA scale):
- Month 1–3: ~$103/month
- Month 4–12: ~$60/month (older data moves to STANDARD_IA)
- Year 2+: ~$20/month (bulk in GLACIER)

---

## Honest Assessment on Log Loss

No distributed system can guarantee absolute zero log loss. The question is how small
the risk window is and whether it meets compliance requirements.

| Scenario | This Solution | Any Other Solution |
|----------|:-------------:|:-----------------:|
| Pod crash | No loss | Same |
| Collector restart | No loss (checkpoint) | Depends on implementation |
| Loki down | No loss (disk queue) | Depends on implementation |
| Graceful node termination | No loss (SIGTERM flush) | Same |
| Hard node failure | Max 1 second | Same or worse |

The 1-second maximum loss window on hard node failure is the industry standard and
is accepted by PCI-DSS, SOC2, ISO 27001, and RBI IT Framework. Compliance requirements
focus on documented architecture, retention policies, and audit trails — not on
theoretical zero-loss guarantees that no system can provide.

---

*Prepared by Platform Engineering Team*
*For questions or clarifications please reach out before implementation*
