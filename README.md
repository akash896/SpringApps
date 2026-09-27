# Resilient Java Microservices

Three self-contained Spring Boot 3 / Java 17 Maven applications plus a metrics dashboard:

1. `pricing-service` (8083) calculates prices.
2. `inventory-service` (8082) obtains a price through REST and supplies stock availability.
3. `order-service` (8081) obtains inventory through REST and accepts or rejects orders.

Each service uses Resilience4j circuit breaker, retry, bulkhead, and rate limiter patterns. Inventory and Order use thread-pool bulkheads; Pricing uses a semaphore bulkhead. Every service defines an active Micrometer `JmxMeterRegistry`, so Micrometer—including Resilience4j instrumentation—is emitted as JMX MBeans. Actuator metrics are also exposed.
Each service also exposes a Hystrix-compatible `/hystrix.stream` endpoint that adapts Resilience4j metrics for dashboard visibility.

## Local run

The easiest local path is the startup script:

```sh
sh start.sh
```

It builds all four jars, starts the three services and the dashboard, and writes
logs under `logs/`. Stop everything with Ctrl-C.

Local URLs:

| Component | URL |
| --- | --- |
| Order service | `http://localhost:8081` |
| Inventory service | `http://localhost:8082` |
| Pricing service | `http://localhost:8083` |
| Hystrix dashboard | `http://localhost:8090` |

You can also start the apps in separate terminals:

```sh
(cd pricing-service && mvn spring-boot:run)
(cd inventory-service && mvn spring-boot:run)
(cd order-service && mvn spring-boot:run)
(cd hystrix-dashboard && mvn spring-boot:run)
```

## Quick API checks

Create an order:

```sh
curl -s -X POST http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"sku":"BOOK-1","quantity":2}'
```

Check service health:

```sh
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8083/actuator/health
curl -s http://localhost:8090/actuator/health
```

## Hystrix dashboard

Open the dashboard:

```text
http://localhost:8090
```

The dashboard reads these proxied streams:

```text
http://localhost:8090/streams/pricing
http://localhost:8090/streams/inventory
http://localhost:8090/streams/order
```

The raw service streams are also exposed directly:

```text
http://localhost:8083/hystrix.stream
http://localhost:8082/hystrix.stream
http://localhost:8081/hystrix.stream
```

Sample one stream from the terminal:

```sh
curl -N http://localhost:8090/streams/order
```

## Load tests

Run normal continuous traffic:

```sh
python3 loadTest.py
```

Run heavy continuous traffic to exercise rate limiter, circuit breaker, retry,
and bulkhead metrics:

```sh
python3 heavyLoadTest.py
```

Increase heavy-load concurrency:

```sh
python3 heavyLoadTest.py --workers 150
```

Both scripts keep running until Ctrl-C.

## JMX metrics

Each service publishes Micrometer and Resilience4j metrics as JMX MBeans through
an active `JmxMeterRegistry`. While the services are running, open JConsole or
VisualVM and inspect the `metrics` domain for meters such as:

```text
resilience4j.circuitbreaker.calls
resilience4j.circuitbreaker.state
resilience4j.bulkhead.available.concurrent.calls
resilience4j.ratelimiter.available.permissions
orders.accepted
```

## Docker Compose

Run the complete stack with Docker:

```sh
docker compose up --build
```

Then open:

```text
http://localhost:8090
```

Stop and remove the Compose stack:

```sh
docker compose down
```

## Kubernetes clusters

Kubernetes manifests for the three-cluster, four-replica-per-service topology
are in [kubernetes/README.md](kubernetes/README.md).
