# What We Monitor — A Complete Guide to Our Grafana Dashboards

We built four purpose-built Grafana dashboards, each targeting a different audience and use case. This document walks through every section and panel, explaining what it shows, why it matters, and what action to take when something looks wrong.

---

## Dashboard 1 — APM Dashboard
**File:** `apm-dashboard.json`
**Audience:** Developers, SRE, On-call engineers
**Purpose:** Application performance — is my service fast, reliable, and healthy?

### Data Source
All panels are powered by **spanmetrics** — Prometheus metrics automatically derived from distributed traces by the OTel Collector's spanmetrics connector. No manual instrumentation needed.

---

### Section: Service Overview

**Request Rate (req/s)**
- What: Requests per second hitting each service, broken down by service name
- Metric: `otel_traces_spanmetrics_calls_total`
- Why it matters: Sudden drop = traffic stopped (deployment issue, load balancer problem). Sudden spike = traffic surge or retry storm.

**Error Rate (%)**
- What: Percentage of requests returning error status per service
- Metric: `rate(calls_total{status_code="STATUS_CODE_ERROR"}) / rate(calls_total) * 100`
- Threshold: Yellow > 1%, Red > 5%
- Why it matters: Rising error rate is the first sign of a broken deployment or downstream dependency failure.

**P50 / P95 / P99 Latency**
- What: Latency percentiles per service in milliseconds
- Metric: `histogram_quantile(0.95, rate(duration_milliseconds_bucket[5m]))`
- Why it matters: P50 = typical user experience. P95/P99 = worst-case experience. A high P99 with normal P50 means a small percentage of users are having a terrible experience.

**Apdex Score**
- What: Application Performance Index — a single number (0–1) representing user satisfaction
- Formula: `(satisfied + tolerating/2) / total` where satisfied = < 256ms, tolerating = < 1024ms
- Threshold: Green > 0.9, Yellow > 0.7, Red < 0.7
- Why it matters: Single metric to answer "is my app performing well?" — useful for SLA reporting.

---

### Section: Latency Deep Dive

**Latency Heatmap**
- What: Distribution of request latency over time as a heatmap
- Why it matters: Reveals bimodal distributions (two groups of users with very different experiences) that percentiles alone can hide.

**Top Slow Endpoints**
- What: Table of slowest HTTP endpoints ranked by P95 latency
- Why it matters: Identifies exactly which API endpoint to optimize first.

**DB Query Latency**
- What: Latency of database operations per service
- Metric: Filtered by `db.system` dimension from spanmetrics
- Why it matters: Most latency issues trace back to slow queries. This panel shows it without needing to look at traces.

---

### Section: Traffic Breakdown

**Request Rate by HTTP Method**
- What: GET vs POST vs PUT vs DELETE breakdown
- Why it matters: Unexpected POST spike could mean a retry loop. Unexpected DELETE spike could mean a bug.

**Request Rate by Status Code**
- What: 2xx vs 4xx vs 5xx breakdown over time
- Why it matters: Rising 4xx = client errors (bad requests, auth failures). Rising 5xx = server errors (bugs, resource exhaustion).

---

## Dashboard 2 — JVM Dashboard
**File:** `jvm-dashboard.json`
**Audience:** Java developers, SRE
**Purpose:** JVM internals — is my Java service healthy at the runtime level?

**Variables:** `$service` (cascades to) `$pod` — filter to specific service and pod instance.

---

### Section: Overview

**Heap Used vs Max**
- What: Current heap memory used vs maximum heap size
- Metric: `jvm.memory.used{area="heap"}` vs `jvm.memory.max{area="heap"}`
- Why it matters: Heap approaching max = OutOfMemoryError imminent. Consistently high heap = memory leak or undersized heap.

**Non-Heap Memory**
- What: Metaspace, code cache, compressed class space usage
- Why it matters: Metaspace growing continuously = class loader leak (common in dynamic frameworks).

**GC Pause Time**
- What: Time spent in garbage collection pauses
- Metric: `jvm.gc.duration` histogram
- Why it matters: Long GC pauses (> 500ms) cause request timeouts and latency spikes. Frequent GC = memory pressure.

**Thread Count**
- What: Total active threads
- Why it matters: Thread count growing unboundedly = thread leak. Sudden spike = thread pool exhaustion.

---

### Section: Memory

**Heap Memory — Used / Committed / Max**
- What: Three-line chart showing heap utilization over time
- Why it matters: Gap between committed and max = headroom. Used approaching committed = JVM will request more memory from OS.

**Non-Heap Memory — Used / Committed**
- What: Metaspace and code cache over time
- Why it matters: Metaspace growing after each deployment = old classloaders not being GC'd.

**Memory Pool Breakdown**
- What: Eden space, Survivor space, Old Gen usage separately
- Why it matters: Old Gen growing = long-lived objects accumulating, potential memory leak.

---

### Section: Garbage Collection

**GC Collections per Minute**
- What: Rate of minor (Young GC) and major (Full GC) collections
- Metric: `jvm.gc.collections`
- Why it matters: Frequent Full GC = serious memory pressure. Full GC pauses the entire JVM (stop-the-world).

**GC Pause Duration**
- What: P50/P95/P99 GC pause time
- Why it matters: P99 GC pause > 1s will cause request timeouts in services with tight SLAs.

**GC Overhead %**
- What: Percentage of time spent in GC vs doing actual work
- Why it matters: > 10% GC overhead = application is struggling. > 25% = critical, needs immediate attention.

---

### Section: Threads

**Thread States**
- What: Stacked chart of threads by state — RUNNABLE, BLOCKED, WAITING, TIMED_WAITING
- Metric: `jvm.threads.count` by state
- Why it matters: High BLOCKED threads = lock contention. High WAITING threads = thread pool waiting for work (normal) or deadlock (bad).

**Daemon vs Non-Daemon Threads**
- What: Breakdown of daemon vs user threads
- Why it matters: Non-daemon thread leak will prevent JVM from shutting down cleanly.

---

### Section: CPU

**JVM CPU Usage %**
- What: CPU consumed by the JVM process
- Metric: `process.cpu.usage`
- Why it matters: High CPU with low request rate = GC pressure or CPU-intensive background work.

**System CPU Usage %**
- What: Total system CPU on the node
- Why it matters: Correlate JVM CPU with system CPU to understand if the JVM is the bottleneck.

---

### Section: Classes

**Loaded Classes**
- What: Number of classes currently loaded in the JVM
- Metric: `jvm.classes.loaded`
- Why it matters: Growing class count after deployments = classloader leak.

**Class Loading Rate**
- What: Rate of classes being loaded and unloaded
- Why it matters: High unload rate = dynamic class generation (reflection-heavy frameworks). Zero unloads with growing loads = leak.

---

## Dashboard 3 — EKS Cluster & Workload Monitoring
**File:** `eks-dashboard.json`
**Audience:** NOC, Platform/Infrastructure team, On-call engineers
**Purpose:** Cluster health, workload status, resource utilization, security compliance

**Variables:** `$namespace` — filter all pod/deployment panels to specific namespace.

---

### Section 1: NOC — Active Issues

This section is designed to be the **first thing an on-call engineer looks at**. All panels use `colorMode: background` — the entire panel turns red when there is a problem.

**Service Health Status (full width)**
- What: UP/DOWN status for every service, probed every 15 seconds via Blackbox Exporter
- Shows: Green = UP, Red = DOWN per service name
- Action: Any red panel = immediate investigation. Check pod status, logs, recent deployments.

**CrashLoopBackOff Pods**
- What: Count of pods stuck in crash loop with sparkline history
- Metric: `count(kube_pod_container_status_waiting_reason{reason="CrashLoopBackOff"})`
- Action: Check `kubectl describe pod` and `kubectl logs` for the failing pod.

**ImagePullBackOff Pods**
- What: Count of pods failing to pull their container image
- Action: Check ECR permissions, image tag exists, network connectivity to registry.

**Nodes NotReady**
- What: Count of nodes in NotReady state
- Metric: `count(kube_node_status_condition{condition="NotReady"})`
- Action: Check node events, EC2 instance health in AWS console, kubelet logs.

**Unschedulable Pods**
- What: Pods stuck in Pending because no node can fit them
- Action: Check resource requests vs node capacity, node taints, PVC availability.

**PVC Near Full (>80%)**
- What: Count of persistent volumes over 80% capacity
- Action: Expand PVC, clean up old data, or add storage class auto-expansion.

**CPU Throttled Containers**
- What: Count of containers being CPU throttled
- Action: Increase CPU limits or optimize application CPU usage.

**Problem Pods Table**
- What: Full table of all pods with issues — CrashLoopBackOff, ImagePullBackOff, OOMKilled, Error
- Shows: Namespace, Pod, Container, Reason with red color-background on Reason column
- Action: This is the starting point for any incident. Sort by Reason to prioritize.

---

### Section 2: Cluster Overview

Six stat panels giving a quick health snapshot of the entire cluster.

| Panel | Metric | Green | Yellow | Red |
|---|---|---|---|---|
| Nodes | `count(kube_node_info)` | Any | — | — |
| Running Pods | `sum(kube_pod_status_phase{phase="Running"})` | Any | — | — |
| Failed Pods | `sum(kube_pod_status_phase{phase="Failed"})` | 0 | — | > 0 |
| Pending Pods | `sum(kube_pod_status_phase{phase="Pending"})` | 0 | > 0 | — |
| Cluster CPU % | avg across all nodes | < 70% | 70–85% | > 85% |
| Cluster Memory % | avg across all nodes | < 70% | 70–85% | > 85% |

---

### Section 3: API Health — Service Up/Down

**API Response Time**
- What: End-to-end probe duration per service over time
- Threshold lines: Yellow at 1s, Red at 2s drawn on the chart
- Why it matters: Gradual increase = memory leak or resource exhaustion. Sudden spike = deployment issue.

**Probe Success History**
- What: Step-line chart showing 1 (UP) or 0 (DOWN) per service over time
- Uses `lineInterpolation: stepAfter` so outage windows are clearly visible as flat lines at 0
- Why it matters: Shows exactly when a service went down and came back up.

**HTTP Status Codes**
- What: Scatter plot of HTTP response codes per service
- Value mappings: 200=green, 4xx=orange, 5xx=red
- Why it matters: A service returning 503 is technically "up" but not serving traffic correctly.

**DNS Lookup Time**
- What: Time to resolve DNS for each service endpoint
- Why it matters: DNS spikes cause intermittent failures that are hard to diagnose. Common in EKS with CoreDNS under load.

**SSL Cert Expiry (days)**
- What: Days remaining until SSL certificate expires per service
- Threshold lines: Yellow at 30 days, Red at 7 days
- Why it matters: Expired SSL certs cause complete service outages. This panel gives 30 days warning.

---

### Section 4: Node Metrics

**Node CPU Usage %**
- What: CPU utilization per node over time with gradient fill
- Why it matters: Identify hot nodes. Uneven distribution = pod scheduling imbalance.

**Node Memory Usage %**
- What: Memory utilization per node over time
- Why it matters: Node memory pressure triggers pod evictions.

**Node Disk Usage %**
- What: Root filesystem usage per node
- Why it matters: Full disk = kubelet stops working, pods can't be scheduled.

**Node Network I/O**
- What: Receive (positive) and transmit (negative) bytes per second per node
- Why it matters: Network saturation causes latency spikes across all services on that node.

---

### Section 5: Pod Metrics

**Pod CPU Usage by Namespace**
- What: CPU cores consumed per pod, filterable by `$namespace`
- Why it matters: Identify which pods are consuming the most CPU in a namespace.

**Pod Memory Usage by Namespace**
- What: Working set memory per pod, filterable by `$namespace`
- Why it matters: Identify memory-hungry pods before they trigger OOM kills.

**Pod Restarts (last 1h)**
- What: Bar chart of restart counts per pod in the last hour
- Why it matters: Restarts indicate instability. Even 1 restart per hour compounds to 24/day.

---

### Section 6: Deployment Health

**Deployment Status Table**
- What: Table showing Desired, Ready, and Unavailable replicas per deployment
- Color coding: Ready column = green when > 0, Unavailable column = red when > 0
- Why it matters: Single view of all deployment health across selected namespace.

**Replica Mismatch (Desired vs Ready)**
- What: Timeseries showing deployments where ready < desired (non-zero = degraded)
- Why it matters: Persistent mismatch = pods failing to start, image pull issues, or resource constraints.

**HPA Current vs Max Replicas**
- What: Current and maximum replica count per HPA over time
- Why it matters: Current approaching max = autoscaler at capacity, may need higher max or more nodes.

---

### Section 7: Resource Requests vs Usage

**CPU Reserved % (Scheduler View)**
- What: Total CPU requested by all pods / total allocatable CPU
- Why it matters: This is what the Kubernetes scheduler sees. High value = new pods will fail to schedule even if actual CPU usage is low.

**CPU Actual Usage %**
- What: Real CPU being consumed across the cluster
- Why it matters: Large gap between Reserved and Actual = pods are over-requesting CPU (wasting money and blocking scheduling).

**Memory Reserved % and Actual %**
- Same concept as CPU but for memory.

**CPU/Memory Reserved vs Actual per Node**
- What: Two-line chart per node showing reserved (dashed) vs actual (solid)
- Why it matters: The gap between lines = wasted reserved capacity on that node.

**Pod Resource Requests vs Actual Usage Table**
- What: Table with CPU Request, CPU Actual, Mem Request, Mem Actual per pod
- Why it matters: Identify specific pods that are massively over-provisioned. These are rightsizing candidates.

---

### Section 8: Storage & PVC

**PVCs Near Full / Critical**
- What: Count of PVCs over 80% and 95% capacity
- Action: Expand PVC immediately if critical. Plan expansion if near full.

**PVC Usage % per Volume**
- What: Usage percentage per PVC over time with threshold lines at 80% and 95%
- Why it matters: Gradual growth trend allows proactive expansion before hitting limits.

**PVC Usage Details Table**
- What: Full table with Capacity, Used, Available, and Usage % per PVC
- Usage % column shows as a gradient gauge bar for quick visual scanning
- Why it matters: Single view of all storage health across the cluster.

---

### Section 9: Security & Compliance

**Privileged Containers**
- What: Count of containers running with `privileged: true`
- Why it matters: Privileged containers have full access to the host. Security risk and compliance violation.

**Pods Without Resource Limits**
- What: Count of pods missing CPU or memory limits
- Why it matters: Pods without limits can consume unlimited resources and starve other workloads.

**Pods Without Memory/CPU Limits Tables**
- What: Full list of pods missing limits with namespace and container details
- Why it matters: Actionable list for developers to fix resource configurations.

---

### Section 10: Events Feed — NOC Visibility

**Pod Restart Rate**
- What: Real-time rate of pod restarts as a bar chart
- Why it matters: Spikes indicate active incidents. Useful during deployments to catch immediate failures.

**Warning Events Rate**
- What: Rate of Kubernetes warning events by namespace and reason
- Why it matters: Warning events precede failures. BackOff, FailedScheduling, Unhealthy events appear here first.

**Recent Kubernetes Warning Events Table**
- What: Live feed of all warning events with Count column color-coded (yellow → red)
- Why it matters: The Kubernetes event stream is the most detailed source of "what is happening right now."

**Pod Evictions Table**
- What: Pods evicted due to resource pressure
- Why it matters: Evictions mean a node ran out of memory or disk. Indicates the cluster is under stress.

---

## Dashboard 4 — Cost Optimization Dashboard
**File:** `cost-dashboard.json`
**Audience:** Platform team, Engineering managers, FinOps
**Purpose:** Identify wasted resources, over-provisioned workloads, and idle infrastructure

**Variables:** `$namespace` → `$deployment` → `$pod` (cascading — each filters the next)

---

### Section 1: Cluster Efficiency Overview

Eight stat panels giving a top-level efficiency score for the cluster.

| Panel | What It Shows | Good | Investigate |
|---|---|---|---|
| Cluster CPU Efficiency | Actual CPU / Allocatable CPU % | > 60% | < 40% |
| Cluster Memory Efficiency | Actual Memory / Allocatable Memory % | > 60% | < 40% |
| CPU Request Commitment | Requested CPU / Allocatable CPU % | < 70% | > 90% |
| Memory Request Commitment | Requested Memory / Allocatable Memory % | < 70% | > 90% |
| CPU Waste Ratio | (Requested - Actual) / Requested % | < 40% | > 70% |
| Memory Waste Ratio | (Requested - Actual) / Requested % | < 40% | > 70% |
| Idle Nodes (CPU < 10%) | Nodes with < 10% CPU usage | 0 | > 0 |
| Zombie Pods | Pods with 0 CPU usage | 0 | > 3 |

> **Reading the dashboard:** If CPU Efficiency is 10% but CPU Request Commitment is 80%, your pods are requesting 8x more CPU than they actually use. You are paying for 8x more compute than needed.

---

### Section 2: Node Efficiency

**Node CPU Efficiency % per Node**
- What: Actual CPU usage % per node with threshold lines (Red < 30%, Green > 60%)
- Why it matters: Nodes below 30% are candidates for consolidation or downsizing instance type.

**Node Memory Efficiency % per Node**
- What: Actual memory usage % per node
- Why it matters: Low memory efficiency on large instance types = significant cost waste.

**Node CPU — Reserved vs Actual vs Allocatable**
- What: Three lines per node — what is available, what is requested, what is actually used
- Why it matters: The gap between "reserved" and "actual" is money being wasted. The gap between "allocatable" and "reserved" is scheduling headroom.

**Node Memory — Reserved vs Actual vs Allocatable**
- Same concept for memory.

---

### Section 3: Namespace Resource Consumption

**CPU/Memory Usage by Namespace**
- What: Which namespaces are consuming the most resources over time
- Filterable by `$namespace`
- Why it matters: Identify which team/environment is the biggest consumer.

**CPU/Memory Requested by Namespace**
- What: What each namespace has reserved from the scheduler's perspective
- Why it matters: Compare with actual usage to find over-provisioned namespaces.

**Namespace Resource Summary Table**
- What: CPU Request, CPU Actual, Mem Request, Mem Actual per namespace side by side
- Why it matters: Instantly see which namespace has the largest gap between requested and actual.

---

### Section 4: Pod Rightsizing — Over-Provisioned Workloads

**Top CPU Wasters — Request vs Actual**
- What: Top 10 pods with largest gap between CPU requested and CPU used
- Why it matters: These are the highest-priority pods to rightsize. Reducing their requests frees up scheduling capacity.

**Top Memory Wasters — Request vs Actual**
- What: Top 10 pods with largest gap between memory requested and memory used
- Why it matters: Over-requested memory blocks other pods from scheduling even when memory is physically available.

**Top Over-Provisioned Pods Table**
- What: Full table with CPU Request, CPU Actual, CPU Waste, Mem Request, Mem Actual, Mem Waste
- CPU Waste and Mem Waste columns are color-coded: Green (small waste) → Yellow → Red (large waste)
- Filterable by `$namespace`, `$deployment`, `$pod`
- Why it matters: Actionable list — take the red rows to developers and ask them to reduce resource requests.

> **How to rightsize:** If a pod requests 1 CPU but only uses 50m consistently, set request to 100m (2x actual for headroom) and limit to 500m. This frees 900m CPU on that node.

---

### Section 5: Idle & Zombie Resources

**Pods With Near-Zero CPU Usage**
- What: Scatter plot of pods consuming less than 1m CPU
- Why it matters: These pods are running but doing nothing. Candidates for scale-to-zero or removal.

**CPU Throttling % by Pod**
- What: Percentage of time each pod is being CPU throttled with threshold lines at 25% and 50%
- Why it matters: Throttled pods are slow even when node CPU looks available. Fix: increase CPU limit (not request).

**Zombie Pods Table**
- What: Pods with zero CPU activity in the last 15 minutes
- Why it matters: Running pods cost money. Zombie pods cost money and provide no value.

**Unused PVCs Table**
- What: PVCs that are Bound but not mounted to any running pod
- Metric: `kube_persistentvolumeclaim_status_phase{phase="Bound"} unless on(...) kube_pod_spec_volumes_persistentvolumeclaims_info`
- Why it matters: EBS volumes cost money even when not attached. These are pure waste.

---

### Section 6: HPA & Autoscaling Efficiency

**HPA Utilization % (Current / Max Replicas)**
- What: How close each HPA is to its maximum replica count
- Why it matters: Consistently at 100% = max replicas is too low, service may be under-scaled. Consistently at min = service may be over-scaled.

**HPA Current vs Min vs Max Replicas**
- What: Three lines per HPA — current, minimum, and maximum replicas
- Why it matters: Large gap between current and max during off-hours = min replicas is too high, wasting money overnight.

**Replica Count Over Time by Deployment**
- What: How many replicas each deployment has been running over time
- Filterable by `$namespace` and `$deployment`
- Why it matters: Identify deployments that never scale down — candidates for reducing min replicas.

---

## Summary — What Each Dashboard Answers

| Question | Dashboard |
|---|---|
| Is my service up right now? | EKS Dashboard → NOC section, API Health section |
| Why is my service slow? | APM Dashboard → Latency, Top Slow Endpoints |
| Which request caused the latency spike? | Tempo (via Grafana Explore, linked from APM) |
| What did my app log when it failed? | Loki (via Grafana Explore, linked from traces) |
| Is my Java app leaking memory? | JVM Dashboard → Memory, GC sections |
| Which node is under pressure? | EKS Dashboard → Node Metrics section |
| Why are pods failing to schedule? | EKS Dashboard → Resource Requests vs Usage |
| Which pods are wasting the most money? | Cost Dashboard → Pod Rightsizing section |
| Which namespace costs the most? | Cost Dashboard → Namespace Consumption section |
| Do I have unused storage? | Cost Dashboard → Idle & Zombie Resources |
| Are my SSL certs about to expire? | EKS Dashboard → API Health → SSL Cert Expiry |
| Is my cluster compliant? | EKS Dashboard → Security & Compliance section |
