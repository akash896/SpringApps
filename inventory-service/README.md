# Inventory Service

Runs on port 8082 and calls Pricing Service at `PRICING_SERVICE_URL` (default `http://localhost:8083`).

Start with `mvn spring-boot:run`; call `GET /api/inventory/{sku}`. JMX MBeans are published by the active `JmxMeterRegistry` in the `metrics` JMX domain.
