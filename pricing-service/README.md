# Pricing Service

Runs on port 8083 and exposes `GET /api/prices/{sku}`. Start with `mvn spring-boot:run`.

Micrometer JMX MBeans are active through `JmxMeterRegistry`; inspect the `metrics` JMX domain with JConsole. Resilience4j meters are also available through `/actuator/metrics`.
