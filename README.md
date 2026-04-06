# Monitoring Stack — EKS

> 📖 **Documentation**
> - [01-observability-platform.md](docs/01-observability-platform.md) — Architecture, design decisions, and how everything fits together
> - [02-dashboard-guide.md](docs/02-dashboard-guide.md) — Complete guide to every dashboard section and panel

## Architecture

```
  👤  Users
      |
      v
  ⚖️   Load Balancer  (HTTPS :443)
      |
      v
  +------------------------------------------------------------+
  |  ☁️   EKS Cluster                                          |
  |                                                            |
  |  +------------------------------------------------------+  |
  |  |  📦  Application Namespace                          |  |
  |  |------------------------------------------------------|  |
  |  |  ☕  Java  |  🐍  Python  |  🟩  Node.js            |  |
  |  |  OTel Agent auto-injected via operator annotation   |  |
  |  +------------------------+-----------------------------+  |
  |                           |  OTLP :4318                    |
  |                           v                                |
  |  +------------------------------------------------------+  |
  |  |  🔄  OTel Collector  (DaemonSet)                    |  |
  |  |  Receive -> Process -> spanmetrics -> Export        |  |
  |  +----------+-----------+------------------+-----------+  |
  |             |  Traces   |  Metrics         |  Logs        |
  |             v           v                  v              |
  |  +-----------+  +-------------+  +-----------+            |
  |  | 🔍  Tempo |  | 📊 Prometheus|  | 📝  Loki  |            |
  |  |   50Gi    |  |    50Gi     |  |   50Gi    |            |
  |  +-----------+  +------+------+  +-----------+            |
  |                         |                                  |
  |             scrapes --> 🖥️   node-exporter                 |
  |                     --> ☸️   kube-state-metrics             |
  |                     --> 🔎  blackbox-exporter               |
  |                         |                                  |
  |                         v                                  |
  |  +------------------------------------------------------+  |
  |  |  📈  Grafana                                        |  |
  |  |  📊 APM  |  🖥️ EKS  |  ☕ JVM  |  💰 Cost          |  |
  |  +------------------------------------------------------+  |
  +------------------------------------------------------------+
```

## Grafana Dashboards

### APM Dashboard (`apm-dashboard.json`)

Panel layout order (top to bottom):

| Row | Panels | Description |
|-----|--------|-------------|
| 🚨 NOC — Service Health at a Glance | 6 stat panels (row 1): Availability %, Apdex, Total Requests, Total Errors, Services with Errors, Services Breaching SLA | Services Breaching SLA counts services where error rate >1% OR P95 latency >2s |
| | 6 stat panels (row 2): Throughput, Avg Latency, P50, P95, P99, Error Rate | Golden Signals merged into NOC row as a 2nd stats row |
| | Service Health Status table | One row per service: Req/s, P95, Error %, Total Req (1h) with Tempo/Loki drilldown links |
| 🗺️ Service Dependency Map | Node graph | Service-to-service call map from Tempo |
| 📈 Throughput & Latency Trends | Request Rate, Latency Percentiles, Error Rate % timeseries | 5-minute rolling window |
| 📊 HTTP Status Codes | Status Codes Over Time, Status Code Distribution pie | 2xx/4xx/5xx colour-coded |
| ⚡ Service Comparison | P95 Latency Comparison, Throughput Comparison | Cross-service side-by-side |
| 🔥 Top Transactions | All Endpoints table | Avg/P95/P99/Error % with Tempo + Prometheus + Loki drilldown |
| 🐌 Slowest & Busiest | Top 10 Slowest (P95), Top 10 Highest Throughput bar gauges | Click to open Tempo traces |
| 🔴 Top Errors | Top Error Endpoints table, Error Trends by Endpoint timeseries | Errors/s + Error % |
| 📋 Live Logs | Application Logs panel | Loki logs filtered by `$service` variable |
| 🌐 Client IPs & User Agents | Top Client IPs, Top User Agents tables | Requires `http.client_ip` / `http.user_agent` span attributes |
| 🔗 External Calls & Database | External HTTP Calls table, DB Queries table, latency timeseries | CLIENT span kind |

Variables: `$service` (multi-select, all services from `traces_spanmetrics_calls_total`)

### EKS Dashboard (`eks-dashboard.json`)

Panel layout order (top to bottom):

| Row | Panels | Description |
|-----|--------|-------------|
| 🚨 NOC — Active Issues | CrashLoopBackOff, ImagePullBackOff, Nodes NotReady, Unschedulable Pods, PVC Near Full, CPU Throttled stats + Problem Pods table | First thing NOC checks |
| 🏇 Cluster Overview | Nodes, Total Pods, Failed Pods, Pending Pods, Cluster CPU %, Cluster Memory % | Cluster-wide health at a glance |
| 🖥️ Node Metrics | Node CPU %, Node Memory %, Node Disk %, Node Network I/O timeseries | Per-node breakdown |
| 🩺 Node Conditions & Pressure | Node conditions table (Ready/MemoryPressure/DiskPressure/PIDPressure), Karpenter node activity timeseries | Spot/scale-down events |
| 📦 Pod Metrics | Pod CPU by Namespace, Pod Memory by Namespace, Pod Restarts, OOM Killed Containers | Filtered by `$namespace` |
| 🚀 Deployment Health | Deployment Status table, Replica Mismatch, HPA Current vs Max | Desired vs ready replicas |
| 🌐 API Latency (Ingress) | Request Rate, P95/P50 Latency, Error Rate % timeseries | Span-metrics from OTel |
| 📊 Resource Requests vs Usage | CPU/Memory Reserved % stats, Reserved vs Actual timeseries, Pod resource table | Identify over-provisioned pods |
| 💾 PVC & Storage | PVC usage table (used/capacity/% per PVC) | Alerts before Prometheus/Loki fill up |
| 📐 Container Limits vs Requests | Gauge: CPU limit/request ratio, Memory limit/request ratio per container | Identify missing limits |
| 🏷️ Namespace Resource Quotas | Namespace quota table (CPU/memory used vs hard limit) | Multi-tenant capacity planning |
| ⚠️ Kubernetes Warning Events | K8s warning event log table | Failed scheduling, OOMKill, probe failures |
| 🔒 Network Policy Overview | Network policy count per namespace table | Visibility into policy coverage |

Variables: `$namespace` (multi-select), `$node` (single-select, all nodes)

## What Gets Monitored

| Area | Metrics | Tool |
|------|---------|------|
| **EKS Cluster** | Node CPU/memory/disk, node conditions/pressure, pod restarts, deployment health, HPA, PVC usage, namespace resource quotas, Karpenter node activity, network policies, K8s warning events | Prometheus + kube-state-metrics + node-exporter |
| **Infra (ALB)** | Request count, latency, 4xx/5xx errors, active connections | CloudWatch (AWS native) |
| **APM** | Request latency (p50/p95/p99), throughput, error rates, end-to-end traces, service map, HTTP status codes, service comparison, live logs | OTel + Tempo + Prometheus |
| **Logs** | Container stdout/stderr, log → trace correlation | OTel Collector (filelog) + Loki |
| **Database (RDS)** | Query performance, connections, CPU, I/O, replication lag | CloudWatch Performance Insights |

## Prerequisites

- EKS cluster with kubectl access
- AWS Load Balancer Controller installed (for ALB Ingress)
- EBS CSI Driver installed (for PVCs)
- ACM certificate for Grafana domain

## Directory Structure

```
monitoring/
├── namespace.yaml
├── storageclass.yaml
├── 01-prometheus/
│   ├── rbac.yaml
│   ├── config.yaml
│   ├── prometheus.yaml
│   ├── kube-state-metrics.yaml
│   └── node-exporter.yaml
├── 02-grafana/
│   ├── secret.yaml
│   └── grafana.yaml
├── 03-tempo/
│   └── tempo.yaml
├── 04-otel/
│   ├── collector.yaml
│   └── auto-instrumentation.yaml
├── 05-loki/
│   └── loki.yaml
├── 06-ingress/
│   └── grafana-ingress.yaml
├── 07-dashboards/
│   ├── apm-dashboard.json
│   ├── eks-dashboard.json
│   ├── jvm-dashboard.json
│   └── cost-dashboard.json
├── docs/
│   ├── 01-observability-platform.md
│   └── 02-dashboard-guide.md
└── README.md
```

## Deployment Steps

### Step 1: Prerequisites Check

```bash
# Verify EBS CSI driver
kubectl get pods -n kube-system | grep ebs-csi

# Verify ALB controller
kubectl get pods -n kube-system | grep aws-load-balancer

# If not installed:
# EBS CSI: https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html
# ALB Controller: https://docs.aws.amazon.com/eks/latest/userguide/aws-load-balancer-controller.html
```

**IMPORTANT — EKS Security Group Rule for OTel Operator:**

The OTel Operator runs a webhook on port **9443**. The EKS control plane must be able to reach worker nodes on this port. Without this rule, `kubectl apply` for auto-instrumentation CRDs will fail with `context deadline exceeded`.

Add this ingress rule to your **worker node security group** (in EKS CloudFormation template):

```yaml
  WorkerNodeSecurityGroupIngressFromControlPlaneOtelWebhook:
    Type: AWS::EC2::SecurityGroupIngress
    Properties:
      Description: Allow OTel operator webhook on port 9443 from cluster control plane
      GroupId: !Ref WorkerNodeecurityGroup
      SourceSecurityGroupId: !Ref ControlPlaneSecurityGroup
      IpProtocol: tcp
      FromPort: 9443
      ToPort: 9443
```

Or open all high ports from control plane to nodes (AWS recommended):

```yaml
  WorkerNodeSecurityGroupIngressFromControlPlaneAll:
    Type: AWS::EC2::SecurityGroupIngress
    Properties:
      Description: Allow all traffic from control plane to worker nodes
      GroupId: !Ref WorkerNodeecurityGroup
      SourceSecurityGroupId: !Ref ControlPlaneSecurityGroup
      IpProtocol: tcp
      FromPort: 1025
      ToPort: 65535
```

Verify the rule is in place before proceeding to Step 2.

### Step 2: Install cert-manager + OTel Operator

```bash
# cert-manager (required by OTel operator)
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.20.1/cert-manager.yaml
kubectl wait --for=condition=Available deployment --all -n cert-manager --timeout=300s

# OTel operator
kubectl apply -f https://github.com/open-telemetry/opentelemetry-operator/releases/download/v0.148.0/opentelemetry-operator.yaml
kubectl wait --for=condition=Available deployment --all -n opentelemetry-operator-system --timeout=300s
```

### Step 3: Deploy Monitoring Stack

```bash
# Namespace + StorageClass
kubectl apply -f namespace.yaml
kubectl apply -f storageclass.yaml

# Prometheus (metrics)
kubectl apply -f 01-prometheus/rbac.yaml
kubectl apply -f 01-prometheus/config.yaml
kubectl apply -f 01-prometheus/prometheus.yaml
kubectl apply -f 01-prometheus/kube-state-metrics.yaml
kubectl apply -f 01-prometheus/node-exporter.yaml
kubectl apply -f 01-prometheus/blackbox-exporter.yaml

# Grafana (dashboards)
# IMPORTANT: Update admin password in 02-grafana/secret.yaml first
kubectl apply -f 02-grafana/secret.yaml
kubectl apply -f 02-grafana/grafana.yaml

# Tempo (traces)
kubectl apply -f 03-tempo/tempo.yaml

# OTel Collector + Auto-instrumentation
kubectl apply -f 04-otel/collector.yaml
kubectl apply -f 04-otel/auto-instrumentation.yaml

# Loki (log storage)
kubectl apply -f 05-loki/loki.yaml

# Grafana Ingress
# IMPORTANT: Update <ACM_CERTIFICATE_ARN> and hostname in 06-ingress/grafana-ingress.yaml
kubectl apply -f 06-ingress/grafana-ingress.yaml
```

### Step 4: Verify All Pods Running

```bash
kubectl get pods -n monitoring

# Expected:
# NAME                                  READY   STATUS    RESTARTS
# prometheus-0                          1/1     Running   0
# grafana-xxxxx                         1/1     Running   0
# tempo-0                               1/1     Running   0
# loki-0                                1/1     Running   0
# otel-collector-xxxxx                  1/1     Running   0
# otel-collector-xxxxx                  1/1     Running   0
# kube-state-metrics-xxxxx              1/1     Running   0
# node-exporter-xxxxx (per node)        1/1     Running   0

```

### Step 5: Enable APM on Your Microservices

Add ONE annotation to each service deployment in your devops repo. No code changes.

**Java services:**
```yaml
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-java: "monitoring/java-instrumentation"
      env:
        - name: OTEL_SERVICE_NAME
          value: user-service
```

**Python services:**
```yaml
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-python: "monitoring/python-instrumentation"
      env:
        - name: OTEL_SERVICE_NAME
          value: payment-service
```

**Node.js services:**
```yaml
spec:
  template:
    metadata:
      annotations:
        instrumentation.opentelemetry.io/inject-nodejs: "monitoring/nodejs-instrumentation"
      env:
        - name: OTEL_SERVICE_NAME
          value: notification-service
```

After adding annotation, restart the deployment:
```bash
kubectl rollout restart deployment/<service-name> -n staging
```

### Step 6: Import Grafana Dashboards

Access Grafana via the ALB hostname from your ingress.

Import via **Grafana → Dashboards → Import → Upload JSON file**:

| # | File | Dashboard UID | What It Shows |
|---|------|---------------|---------------|
| 1 | `07-dashboards/apm-dashboard.json` | `apm-main` | **APM** — Golden Signals, Apdex score, P50/P95/P99 latency, error rate, top slow endpoints, service comparison |
| 2 | `07-dashboards/eks-dashboard.json` | `eks-infra-dashboard` | **EKS Cluster** — NOC issues, cluster overview, API health (Blackbox), node metrics, pod metrics, deployment health, storage & PVC, security compliance, events feed, resource requests vs usage |
| 3 | `07-dashboards/jvm-dashboard.json` | `jvm-dashboard` | **JVM** — Heap/non-heap memory, GC pause time & count, thread states, CPU usage, class loading |
| 4 | `07-dashboards/cost-dashboard.json` | `eks-cost-dashboard` | **Cost Optimization** — Cluster efficiency, node utilization, namespace breakdown, pod rightsizing, zombie resources, unused PVCs, HPA efficiency |

> All dashboards use **Prometheus** as the data source with UID `prometheus`.
> Variables are pre-configured — `$namespace`, `$deployment`, `$pod` cascade automatically.

### Step 7: CloudWatch for ALB + RDS

ALB and RDS metrics are in CloudWatch natively. No extra setup needed.

**ALB metrics to monitor in CloudWatch:**
- `RequestCount` — total requests
- `TargetResponseTime` — latency
- `HTTPCode_Target_4XX_Count` — client errors
- `HTTPCode_Target_5XX_Count` — server errors
- `ActiveConnectionCount` — concurrent connections
- `UnHealthyHostCount` — unhealthy targets

**RDS Performance Insights (enable in RDS console):**
- Top SQL queries by wait time
- Active sessions breakdown
- CPU/memory/storage usage
- Connection count
- Read/Write IOPS
- Replication lag (if read replicas)

## Application Flow — Full Trace Path

```
  👤  User
      |
      v
  ⚖️   Load Balancer
      |
      v
  ☕  java-service  (45ms total)
      |
      +--------> 🐍  python-service  (30ms)
      |                  |
      |                  +--------> 🗄️   Database  (30ms)
      |
      |  OTLP  (traces + metrics + logs)
      v
  🔄  OTel Collector
      |
      v
  🔍  Tempo
      |
      v
  📈  Grafana  —  Full trace visible:
                  java (45ms) -> python (30ms) -> DB (30ms)
```

In Grafana Tempo, you see the full trace:
```
ALB → user-service (45ms) → payment-service (120ms) → DB query (30ms)
                           → notification-service (15ms)
```

## Alerting

Pre-configured alerts in Prometheus (01-prometheus/config.yaml):

| Alert | Condition | Severity |
|-------|-----------|----------|
| PodCrashLooping | Restarts in last 15m | Critical |
| PodNotReady | Pod not ready for 5m | Warning |
| HighCpuUsage | Pod CPU > 80% for 5m | Warning |
| HighMemoryUsage | Pod memory > 80% for 5m | Warning |
| DeploymentReplicasMismatch | Desired ≠ ready for 5m | Critical |
| NodeNotReady | Node not ready for 2m | Critical |
| PVCNearFull | PVC > 85% used | Warning |
| PVCCriticalFull | PVC > 95% used | Critical |
| CPUThrottlingHigh | Container throttled > 50% for 10m | Warning |
| HighLatencyP95 | p95 latency > 2s for 5m | Warning |
| HighErrorRate | Error rate > 5% for 5m | Critical |
| ServiceDown | Health probe failing for 1m | Critical |
| ServiceSlowResponse | Response time > 2s for 3m | Warning |
| ServiceHTTPError | Unexpected HTTP status code | Warning |
| SSLCertExpiringSoon | SSL cert expires in < 30 days | Warning |
| SSLCertExpiryCritical | SSL cert expires in < 7 days | Critical |

To receive alerts via Slack/Email, add Alertmanager:
```bash
# TODO: Deploy Alertmanager with Slack/Email config
```

## Storage Summary

| Component | PVC Size | Retention |
|-----------|----------|-----------|
| Prometheus | 50Gi | 30 days |
| Tempo | 50Gi | 14 days |
| Loki | 50Gi | 14 days |
| Grafana | 10Gi | — |

## Troubleshooting

| Issue | Fix |
|-------|-----|
| PVCs stuck in Pending | Check EBS CSI driver: `kubectl get pods -n kube-system \| grep ebs` |
| No metrics in Prometheus | Check targets: `kubectl port-forward svc/prometheus 9090 -n monitoring` → Status → Targets |
| No traces in Tempo | Check OTel collector logs: `kubectl logs -l app=otel-collector -n monitoring` |
| No logs in Loki | Check OTel collector logs: `kubectl logs -l app=otel-collector -n monitoring` — look for filelog receiver errors |
| ALB not created | Check ALB controller logs + Ingress events: `kubectl describe ingress grafana-ingress -n monitoring` |
| Auto-instrumentation not working | Verify OTel operator running + annotation is correct + pod restarted after adding annotation |
| `context deadline exceeded` on auto-instrumentation apply | EKS control plane can't reach worker node port 9443. Add SG rule: allow TCP 9443 from control plane SG to worker node SG |

## Values to Update Before Deploying

| File | Value | Replace With |
|------|-------|-------------|
| `02-grafana/secret.yaml` | `admin-password` | Your base64 encoded password |
| `06-ingress/grafana-ingress.yaml` | `<ACM_CERTIFICATE_ARN>` | Your ACM cert ARN |
| `06-ingress/grafana-ingress.yaml` | `grafana.internal.example.com` | Your Grafana domain |
