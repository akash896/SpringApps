# Hystrix Dashboard

This service renders a Hystrix-style dashboard backed by the Resilience4j metrics streams exposed by the three demo microservices.

Start it with:

```sh
mvn spring-boot:run
```

Then open `http://localhost:8090`.

The dashboard consumes:

- `pricing-service`: `/streams/pricing`
- `inventory-service`: `/streams/inventory`
- `order-service`: `/streams/order`

Each proxy endpoint reads the upstream service's `/hystrix.stream` compatibility endpoint.
