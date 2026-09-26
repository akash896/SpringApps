package com.example.inventory;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class InventoryService {
    private final RestClient pricingClient;

    public InventoryService(
            RestClient.Builder builder,
            @Value("${clients.pricing.base-url}") String pricingUrl) {
        this.pricingClient = builder.baseUrl(pricingUrl).build();
    }

    @CircuitBreaker(name = "pricingService", fallbackMethod = "pricingFallback")
    @Retry(name = "pricingService")
    @Bulkhead(name = "pricingService", type = Bulkhead.Type.THREADPOOL)
    @RateLimiter(name = "pricingService")
    public CompletionStage<InventoryResponse> availability(String sku) {
        PriceQuote quote = pricingClient.get()
                .uri("/api/prices/{sku}", sku)
                .retrieve()
                .body(PriceQuote.class);
        if (quote == null || !quote.available()) {
            throw new IllegalStateException("pricing service did not provide a quote");
        }
        return CompletableFuture.completedFuture(
                new InventoryResponse(
                        sku,
                        quantityFor(sku),
                        quote.unitPrice(),
                        quote.currency(),
                        true));
    }

    public CompletionStage<InventoryResponse> pricingFallback(String sku, Throwable error) {
        return CompletableFuture.completedFuture(
                new InventoryResponse(sku, quantityFor(sku), BigDecimal.ZERO, "USD", false));
    }

    private int quantityFor(String sku) {
        return Math.abs(sku.hashCode() % 25) + 1;
    }
}
