package com.example.order;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OrderService {
    private final RestClient inventoryClient;
    private final Counter acceptedOrders;

    public OrderService(
            RestClient.Builder builder,
            MeterRegistry meterRegistry,
            @Value("${clients.inventory.base-url}") String inventoryUrl) {
        this.inventoryClient = builder.baseUrl(inventoryUrl).build();
        this.acceptedOrders = Counter.builder("orders.accepted")
                .description("Accepted order requests")
                .register(meterRegistry);
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
    @Retry(name = "inventoryService")
    @Bulkhead(name = "inventoryService", type = Bulkhead.Type.THREADPOOL)
    @RateLimiter(name = "inventoryService")
    public CompletionStage<OrderResponse> create(CreateOrderRequest request) {
        InventorySnapshot inventory = inventoryClient.get()
                .uri("/api/inventory/{sku}", request.sku())
                .retrieve()
                .body(InventorySnapshot.class);
        if (inventory == null || !inventory.priceAvailable()) {
            return CompletableFuture.completedFuture(
                    rejected(request, "Price is temporarily unavailable"));
        }
        if (inventory.quantityAvailable() < request.quantity()) {
            return CompletableFuture.completedFuture(
                    rejected(request, "Insufficient inventory"));
        }
        acceptedOrders.increment();
        return CompletableFuture.completedFuture(
                new OrderResponse(
                        UUID.randomUUID().toString(),
                        request.sku(),
                        request.quantity(),
                        inventory.unitPrice().multiply(BigDecimal.valueOf(request.quantity())),
                        inventory.currency(),
                        "ACCEPTED",
                        "Order accepted"));
    }

    public CompletionStage<OrderResponse> inventoryFallback(
            CreateOrderRequest request,
            Throwable error) {
        return CompletableFuture.completedFuture(
                new OrderResponse(
                        null,
                        request.sku(),
                        request.quantity(),
                        BigDecimal.ZERO,
                        "USD",
                        "PENDING",
                        "Inventory is temporarily unavailable"));
    }

    private OrderResponse rejected(CreateOrderRequest request, String message) {
        return new OrderResponse(
                null,
                request.sku(),
                request.quantity(),
                BigDecimal.ZERO,
                "USD",
                "REJECTED",
                message);
    }
}
