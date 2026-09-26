package com.example.pricing;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PricingController {
    @GetMapping("/api/prices/{sku}")
    @CircuitBreaker(name = "pricingApi", fallbackMethod = "unavailable")
    @Retry(name = "pricingApi")
    @Bulkhead(name = "pricingApi", type = Bulkhead.Type.SEMAPHORE)
    @RateLimiter(name = "pricingApi")
    public QuoteResponse quote(@PathVariable String sku) {
        if (sku.isBlank()) throw new IllegalArgumentException("sku must not be blank");
        BigDecimal price = new BigDecimal(Math.abs(sku.hashCode() % 9000) + 1000).movePointLeft(2);
        return new QuoteResponse(sku, price, "USD", true);
    }

    public QuoteResponse unavailable(String sku, Throwable error) {
        return new QuoteResponse(sku, BigDecimal.ZERO, "USD", false);
    }
}
