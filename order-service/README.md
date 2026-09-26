# Order Service

Runs on port 8081 and calls Inventory Service at `INVENTORY_SERVICE_URL` (default `http://localhost:8082`).

Start all three services, then submit an order:

```sh
curl -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' -d '{"sku":"BOOK-1","quantity":2}'
```

`JmxMeterRegistry` is a live bean, publishing the custom `orders.accepted` meter and Resilience4j meters as JMX MBeans. Use JConsole's `metrics` domain or `/actuator/metrics` to inspect them.
