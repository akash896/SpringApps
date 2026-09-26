# Resilient Java Microservices

Three self-contained Spring Boot 3 / Java 17 Maven applications:

1. `pricing-service` (8083) calculates prices.
2. `inventory-service` (8082) obtains a price through REST and supplies stock availability.
3. `order-service` (8081) obtains inventory through REST and accepts or rejects orders.

Each service uses Resilience4j circuit breaker, retry, semaphore bulkhead, and rate limiter patterns. Every service defines an active Micrometer `JmxMeterRegistry`, so Micrometer—including Resilience4j instrumentation—is emitted as JMX MBeans. Actuator metrics are also exposed.

Start the services in dependency order in separate terminals:

```sh
(cd pricing-service && mvn spring-boot:run)
(cd inventory-service && mvn spring-boot:run)
(cd order-service && mvn spring-boot:run)
```

Then invoke `POST http://localhost:8081/api/orders` with `{"sku":"BOOK-1","quantity":2}`.

To run the complete stack with Docker, use `docker compose up --build` from this directory.
