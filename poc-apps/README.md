# POC — Monitoring Stack Validation

## What's Included

| App | Language | Endpoints | OTel Annotation |
|-----|----------|-----------|-----------------|
| java-demo-app | Java (Spring Boot) | `/greeting` | `inject-java` |
| python-demo-app | Python (Flask) | `/api/payment`, `/api/payment/validate`, `/api/call-java` | `inject-python` |
| load-generator | curl | Hits both apps every 2s | — |

## Flow

```
load-generator
    │
    ├──► java-demo-app/greeting
    │
    ├──► python-demo-app/api/payment          (10% error rate simulated)
    │
    ├──► python-demo-app/api/payment/validate
    │
    └──► python-demo-app/api/call-java ──► java-demo-app/greeting
                                            (cross-service trace)
```

## Deploy

```bash
# Ensure monitoring stack is running first
kubectl get pods -n monitoring

# Create staging namespace
kubectl create namespace staging --dry-run=client -o yaml | kubectl apply -f -

# Deploy apps
kubectl apply -f java-demo-app.yaml
kubectl apply -f python-demo-app.yaml
kubectl apply -f load-generator.yaml

# Wait for pods
kubectl get pods -n staging -w
```

## Verify in Grafana (wait 2-3 minutes after deploy)

### 1. Traces (Tempo)
- Go to Explore → Select Tempo datasource
- Search → Service Name = `python-demo-app` → Run query
- You should see traces with spans
- Click a trace from `/api/call-java` → see cross-service trace:
  ```
  python-demo-app (api/call-java) → java-demo-app (greeting)
  ```

### 2. Service Map (Tempo)
- Go to Explore → Tempo → Service Graph tab
- You should see: `python-demo-app` → `java-demo-app` with latency on edges

### 3. Metrics (Prometheus)
- Go to Explore → Prometheus datasource
- Query: `rate(container_cpu_usage_seconds_total{pod=~"java-demo.*|python-demo.*"}[5m])`
- You should see CPU usage for both apps

### 4. Logs (Loki)
- Go to Explore → Loki datasource
- Query: `{app="python-demo-app"}`
- You should see: "Payment processed in 0.xxxs" and "Payment processing failed" logs
- Click trace ID in log → jumps to Tempo trace

### 5. APM Metrics (OTel → Prometheus)
- Latency: `histogram_quantile(0.95, sum(rate(duration_milliseconds_bucket{service_name="python-demo-app"}[5m])) by (le))`
- Error rate: `sum(rate(calls_total{status_code="STATUS_CODE_ERROR", service_name="python-demo-app"}[5m]))`
- Throughput: `sum(rate(calls_total{service_name="python-demo-app"}[5m]))`

## Cleanup

```bash
kubectl delete -f load-generator.yaml
kubectl delete -f python-demo-app.yaml
kubectl delete -f java-demo-app.yaml
```
