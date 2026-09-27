package com.example.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class HystrixStreamController {
    private final CircuitBreakerRegistry circuitBreakers;
    private final BulkheadRegistry bulkheads;
    private final RetryRegistry retries;
    private final RateLimiterRegistry rateLimiters;
    private final ThreadPoolBulkheadRegistry threadPoolBulkheads;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService executor;

    public HystrixStreamController(
            CircuitBreakerRegistry circuitBreakers,
            BulkheadRegistry bulkheads,
            RetryRegistry retries,
            RateLimiterRegistry rateLimiters,
            ObjectProvider<ThreadPoolBulkheadRegistry> threadPoolBulkheads,
            ObjectMapper objectMapper) {
        this.circuitBreakers = circuitBreakers;
        this.bulkheads = bulkheads;
        this.retries = retries;
        this.rateLimiters = rateLimiters;
        this.threadPoolBulkheads = threadPoolBulkheads.getIfAvailable();
        this.objectMapper = objectMapper;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hystrix-stream-inventory");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping(path = "/hystrix.stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        executor.scheduleAtFixedRate(() -> sendSnapshot(emitter), 0, 1, TimeUnit.SECONDS);
        return emitter;
    }

    private void sendSnapshot(SseEmitter emitter) {
        for (CircuitBreaker circuitBreaker : circuitBreakers.getAllCircuitBreakers()) {
            try {
                emitter.send(toJson(snapshot(circuitBreaker)));
            } catch (IOException error) {
                emitter.completeWithError(error);
            }
        }
    }

    private Map<String, Object> snapshot(CircuitBreaker circuitBreaker) {
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        String name = circuitBreaker.getName();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("type", "HystrixCommand");
        document.put("name", name);
        document.put("group", "inventory-service");
        document.put("currentTime", Instant.now().toEpochMilli());
        document.put("isCircuitBreakerOpen", circuitBreaker.getState() == CircuitBreaker.State.OPEN);
        document.put("circuitBreakerState", circuitBreaker.getState().name());
        document.put("errorPercentage", safePercentage(metrics.getFailureRate()));
        document.put("errorCount", metrics.getNumberOfFailedCalls());
        document.put("requestCount", metrics.getNumberOfBufferedCalls());
        document.put("rollingCountSuccess", metrics.getNumberOfSuccessfulCalls());
        document.put("rollingCountFailure", metrics.getNumberOfFailedCalls());
        document.put("rollingCountShortCircuited", metrics.getNumberOfNotPermittedCalls());
        document.put("rollingCountSemaphoreRejected", semaphoreRejected(name));
        document.put("rollingCountThreadPoolRejected", threadPoolRejected(name));
        document.put("rollingCountTimeout", 0);
        document.put("rollingCountFallbackSuccess", 0);
        document.put("rollingCountFallbackFailure", 0);
        document.put("rollingCountBadRequests", 0);
        document.put("latencyExecute_mean", 0);
        document.put("latencyTotal_mean", 0);
        document.put("propertyValue_circuitBreakerRequestVolumeThreshold", 1);
        document.put("propertyValue_circuitBreakerErrorThresholdPercentage", 50);
        document.put("propertyValue_circuitBreakerSleepWindowInMilliseconds", 10000);
        document.put("reportingHosts", 1);
        document.put("resilience4j", resilience4jDetails(name));
        return document;
    }

    private Map<String, Object> resilience4jDetails(String name) {
        Map<String, Object> details = new LinkedHashMap<>();
        circuitBreakers.find(name).ifPresent(circuitBreaker -> {
            CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
            details.put("slowCallRate", safePercentage(metrics.getSlowCallRate()));
            details.put("slowCalls", metrics.getNumberOfSlowCalls());
        });
        retries.find(name).ifPresent(retry -> details.put("retry", retryMetrics(retry)));
        rateLimiters.find(name).ifPresent(rateLimiter -> details.put("rateLimiter", rateLimiterMetrics(rateLimiter)));
        bulkheads.find(name).ifPresent(bulkhead -> details.put("bulkhead", bulkheadMetrics(bulkhead)));
        if (threadPoolBulkheads != null) {
            threadPoolBulkheads.find(name)
                    .ifPresent(threadPoolBulkhead -> details.put("threadPoolBulkhead", threadPoolMetrics(threadPoolBulkhead)));
        }
        return details;
    }

    private Map<String, Object> retryMetrics(Retry retry) {
        Retry.Metrics metrics = retry.getMetrics();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("successfulWithoutRetry", metrics.getNumberOfSuccessfulCallsWithoutRetryAttempt());
        values.put("successfulWithRetry", metrics.getNumberOfSuccessfulCallsWithRetryAttempt());
        values.put("failedWithoutRetry", metrics.getNumberOfFailedCallsWithoutRetryAttempt());
        values.put("failedWithRetry", metrics.getNumberOfFailedCallsWithRetryAttempt());
        return values;
    }

    private Map<String, Object> rateLimiterMetrics(RateLimiter rateLimiter) {
        RateLimiter.Metrics metrics = rateLimiter.getMetrics();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("availablePermissions", metrics.getAvailablePermissions());
        values.put("waitingThreads", metrics.getNumberOfWaitingThreads());
        return values;
    }

    private Map<String, Object> bulkheadMetrics(Bulkhead bulkhead) {
        Bulkhead.Metrics metrics = bulkhead.getMetrics();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("availableConcurrentCalls", metrics.getAvailableConcurrentCalls());
        values.put("maxAllowedConcurrentCalls", metrics.getMaxAllowedConcurrentCalls());
        return values;
    }

    private Map<String, Object> threadPoolMetrics(ThreadPoolBulkhead threadPoolBulkhead) {
        ThreadPoolBulkhead.Metrics metrics = threadPoolBulkhead.getMetrics();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("threadPoolSize", metrics.getThreadPoolSize());
        values.put("coreThreadPoolSize", metrics.getCoreThreadPoolSize());
        values.put("maximumThreadPoolSize", metrics.getMaximumThreadPoolSize());
        values.put("queueDepth", metrics.getQueueDepth());
        values.put("queueCapacity", metrics.getQueueCapacity());
        values.put("remainingQueueCapacity", metrics.getRemainingQueueCapacity());
        return values;
    }

    private int semaphoreRejected(String name) {
        return bulkheads.find(name)
                .map(Bulkhead::getMetrics)
                .map(metrics -> metrics.getMaxAllowedConcurrentCalls() - metrics.getAvailableConcurrentCalls())
                .orElse(0);
    }

    private int threadPoolRejected(String name) {
        if (threadPoolBulkheads == null) {
            return 0;
        }
        return threadPoolBulkheads.find(name)
                .map(ThreadPoolBulkhead::getMetrics)
                .map(metrics -> metrics.getQueueCapacity() - metrics.getRemainingQueueCapacity())
                .orElse(0);
    }

    private int safePercentage(float value) {
        if (value < 0 || Float.isNaN(value) || Float.isInfinite(value)) {
            return 0;
        }
        return Math.round(value);
    }

    private String toJson(Map<String, Object> document) throws JsonProcessingException {
        return objectMapper.writeValueAsString(document);
    }
}
