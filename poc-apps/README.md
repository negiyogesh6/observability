# POC — Monitoring Stack Validation

## What's Deployed

| App | Language | Port | Endpoints | OTel Annotation |
|-----|----------|------|-----------|-----------------|
| `springboot-demo` | Java (Spring Boot) | 8080 | `/api/health`, `/api/payment`, `/api/payment/validate`, `/api/payment/history`, `/api/user/{id}`, `/api/order/{id}` | `inject-java` |
| `python-demo-app` | Python (Flask) | 5000 | `/health`, `/api/payment`, `/api/payment/validate`, `/api/call-nodejs` | `inject-python` |
| `nodejs-demo-app` | Node.js | 3000 | `/health`, `/` | `inject-nodejs` |
| `load-generator` | curl | — | Hits all apps every 2s | — |

## Traffic Flow

```
  load-generator
      |
      |---> POST springboot-demo/api/payment          (10% error rate)
      |---> GET  springboot-demo/api/payment/validate
      |---> GET  springboot-demo/api/payment/history
      |---> GET  springboot-demo/api/user/123
      |---> GET  springboot-demo/api/order/ORD-456    (5% 404 rate)
      |
      |---> GET  nodejs-demo-app/                     (50-250ms random delay)
      |
      |---> GET  python-demo-app/api/payment          (10% error rate)
      |---> GET  python-demo-app/api/payment/validate
      +---> GET  python-demo-app/api/call-nodejs ----> nodejs-demo-app/
                                                       (cross-service trace)
```

## springboot-demo — Endpoints

| Method | Endpoint | Behaviour |
|--------|----------|-----------|
| GET | `/api/health` | Returns `{"status":"UP"}` — used by readiness/liveness probe |
| POST | `/api/payment` | 50–350ms delay, 10% error rate, logs success/failure |
| GET | `/api/payment/validate` | 20–120ms delay, validates txnId |
| GET | `/api/payment/history` | 100–300ms delay, returns random count |
| GET | `/api/user/{userId}` | 30–180ms delay, returns user info |
| GET | `/api/order/{orderId}` | 80–330ms delay, 5% returns 404 |

> Built with Spring Boot, auto-instrumented via OTel Java agent v2.9.0.
> Exports traces, metrics (including JVM), and logs to OTel Collector.

## Deploy

```bash
# Ensure monitoring stack is running first
kubectl get pods -n monitoring

# Create staging namespace
kubectl create namespace staging --dry-run=client -o yaml | kubectl apply -f -

# Deploy all apps
kubectl apply -f springboot-demo.yaml
kubectl apply -f python-demo-app.yaml
kubectl apply -f nodejs-demo-app.yaml
kubectl apply -f load-generator.yaml

# Wait for pods to be ready
kubectl get pods -n staging -w
```

## Verify Pods Running

```bash
kubectl get pods -n staging

# Expected:
# NAME                               READY   STATUS    RESTARTS
# springboot-demo-xxxx-xxxx          1/1     Running   0
# python-demo-app-xxxx-xxxx          1/1     Running   0
# nodejs-demo-app-xxxx-xxxx          1/1     Running   0
# load-generator-xxxx-xxxx           1/1     Running   0
```

## Verify in Grafana (wait 2–3 minutes after deploy)

### 1. Traces (Tempo)
- Go to **Explore → Tempo datasource**
- Search → Service Name = `springboot-demo` → Run query
- Click any trace → see spans for `/api/payment` with latency breakdown
- Search Service Name = `python-demo-app` → click `/api/call-nodejs` trace
- You should see cross-service trace:
  ```
  python-demo-app (api/call-nodejs) --> nodejs-demo-app (/)
  ```

### 2. Service Map (Tempo)
- Go to **Explore → Tempo → Service Graph tab**
- You should see:
  ```
  load-generator --> springboot-demo
  load-generator --> python-demo-app --> nodejs-demo-app
  ```

### 3. APM Dashboard
- Open **📊 APM Dashboard**
- Select `springboot-demo` from `$service` variable
- Verify: Request Rate, Error Rate (~10%), P95 Latency, Apdex Score

### 4. JVM Dashboard
- Open **☕ JVM Dashboard**
- Select `springboot-demo` from `$service` variable
- Verify: Heap memory, GC pause time, thread count, CPU usage

### 5. Metrics (Prometheus)
- Go to **Explore → Prometheus datasource**
- CPU usage:
  ```
  rate(container_cpu_usage_seconds_total{namespace="staging"}[5m])
  ```
- Request rate:
  ```
  sum(rate(otel_traces_spanmetrics_calls_total{service_name="springboot-demo"}[5m]))
  ```
- P95 latency:
  ```
  histogram_quantile(0.95, sum(rate(otel_traces_spanmetrics_duration_milliseconds_bucket{service_name="springboot-demo"}[5m])) by (le))
  ```

### 6. Logs (Loki)
- Go to **Explore → Loki datasource**
- Query: `{namespace="staging", app="springboot-demo"}`
- You should see:
  - `Payment processed successfully in XXXms`
  - `Payment processing failed after XXXms`
  - `Order ORD-456 not found`
- Click a trace ID in a log line → jumps directly to Tempo trace

### 7. EKS Dashboard
- Open **🖥️ EKS Dashboard**
- Select `staging` from `$namespace` variable
- Verify: Pod CPU/memory usage, restart count, resource requests vs actual

### 8. API Health (Blackbox)
- Open **🖥️ EKS Dashboard → API Health section**
- Verify `springboot-demo`, `python-demo-app`, `nodejs-demo-app` all show **UP**

## Cleanup

```bash
kubectl delete -f load-generator.yaml
kubectl delete -f springboot-demo.yaml
kubectl delete -f python-demo-app.yaml
kubectl delete -f nodejs-demo-app.yaml
```
