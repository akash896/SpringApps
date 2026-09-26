# Resilient Java Microservices

Three self-contained Spring Boot 3 / Java 17 Maven applications:

1. `pricing-service` (8083) calculates prices.
2. `inventory-service` (8082) obtains a price through REST and supplies stock availability.
3. `order-service` (8081) obtains inventory through REST and accepts or rejects orders.

Each service uses Resilience4j circuit breaker, retry, bulkhead, and rate limiter patterns. Inventory and Order use thread-pool bulkheads; Pricing uses a semaphore bulkhead. Every service defines an active Micrometer `JmxMeterRegistry`, so Micrometer—including Resilience4j instrumentation—is emitted as JMX MBeans. Actuator metrics are also exposed.

Start the services in dependency order in separate terminals:

```sh
(cd pricing-service && mvn spring-boot:run)
(cd inventory-service && mvn spring-boot:run)
(cd order-service && mvn spring-boot:run)
```

Then invoke `POST http://localhost:8081/api/orders` with `{"sku":"BOOK-1","quantity":2}`.

To run the complete stack with Docker, use `docker compose up --build` from this directory.

For local Maven execution, run `./start.sh` or `sh start.sh`. It builds all services using the project-local Maven configuration, then starts them and writes output to `logs/`.
Use `python3 loadTest.py` for continuous normal traffic and `python3 heavyLoadTest.py` for continuous high concurrency that exercises rate-limit and bulkhead metrics. Stop either generator with Ctrl-C.
