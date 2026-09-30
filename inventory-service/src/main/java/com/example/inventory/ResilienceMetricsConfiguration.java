package com.example.inventory;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import java.lang.management.ManagementFactory;

/**
 * Supplements native Resilience4j meters with cumulative durations and rejection counts.
 * Values are recorded from real events; no dashboard library or synthetic values are used.
 * These services use fixed, YAML-defined registries, bound once after bean initialization.
 */
@Configuration(proxyBeanMethods = false)
public class ResilienceMetricsConfiguration {
    private static final String DASHBOARD_TIMING_MBEAN =
            "dev.resilience4j.live:type=DashboardConfiguration";

    @Bean(destroyMethod = "close")
    DashboardTimingRegistration dashboardTiming(Environment environment) throws Exception {
        long poll = environment.getRequiredProperty(
                "resilience4j.dashboard.pollIntervalMillis", Long.class);
        long browser = environment.getRequiredProperty(
                "resilience4j.dashboard.browserPublishIntervalMillis", Long.class);
        return new DashboardTimingRegistration(poll, browser);
    }

    @Bean
    SmartInitializingSingleton additionalResilienceMetrics(
            MeterRegistry meters, CircuitBreakerRegistry circuits, BulkheadRegistry bulkheads,
            ThreadPoolBulkheadRegistry pools, RateLimiterRegistry limiters) {
        return () -> {
            circuits.getAllCircuitBreakers().forEach(circuit -> {
                String name = circuit.getName();
                Counter count = counter(meters, "circuit.duration.count", name);
                // Integer nanoseconds survive the JMX meter's integral Count attribute.
                Counter nanos = counter(meters, "circuit.duration.nanos", name);
                Counter rejected = counter(meters, "circuit.rejected", name);
                Counter timeouts = counter(meters, "circuit.timeouts", name);
                circuit.getEventPublisher().onSuccess(event -> {
                    nanos.increment(event.getElapsedDuration().toNanos());
                    count.increment();
                }).onError(event -> {
                    nanos.increment(event.getElapsedDuration().toNanos());
                    count.increment();
                    Throwable cause = event.getThrowable();
                    boolean rejection = false, timeout = false;
                    for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                        rejection |= cause instanceof BulkheadFullException || cause instanceof RequestNotPermitted;
                        timeout |= cause instanceof java.util.concurrent.TimeoutException
                                || cause instanceof java.net.SocketTimeoutException
                                || cause instanceof java.net.http.HttpTimeoutException;
                    }
                    if (rejection) rejected.increment();
                    if (timeout) timeouts.increment();
                });
            });
            bulkheads.getAllBulkheads().forEach(bulkhead -> {
                Counter rejected = counter(meters, "bulkhead.rejected", bulkhead.getName());
                bulkhead.getEventPublisher().onCallRejected(event -> rejected.increment());
            });
            pools.getAllBulkheads().forEach(pool -> {
                Counter rejected = counter(meters, "pool.rejected", pool.getName());
                Counter completed = counter(meters, "pool.completed", pool.getName());
                pool.getEventPublisher().onCallRejected(event -> rejected.increment())
                        .onCallFinished(event -> completed.increment());
                Gauge.builder("application.resilience.pool.remaining", pool,
                                value -> value.getMetrics().getRemainingQueueCapacity())
                        .tag("name", pool.getName()).register(meters);
            });
            limiters.getAllRateLimiters().forEach(limiter -> {
                Counter rejected = counter(meters, "ratelimiter.rejected", limiter.getName());
                limiter.getEventPublisher().onFailure(event -> rejected.increment());
            });
        };
    }

    private static Counter counter(MeterRegistry meters, String metric, String name) {
        return Counter.builder("application.resilience." + metric).tag("name", name).register(meters);
    }

    /** Stable JMX attributes consumed by the framework-independent cluster collector. */
    public interface DashboardTimingMXBean {
        long getPollIntervalMillis();
        long getBrowserPublishIntervalMillis();
    }

    private record DashboardTiming(long pollIntervalMillis, long browserPublishIntervalMillis)
            implements DashboardTimingMXBean {
        private DashboardTiming {
            if (pollIntervalMillis < 1)
                throw new IllegalArgumentException("pollIntervalMillis must be at least 1 ms");
            if (browserPublishIntervalMillis < 1)
                throw new IllegalArgumentException("browserPublishIntervalMillis must be at least 1 ms");
        }
        @Override public long getPollIntervalMillis() { return pollIntervalMillis; }
        @Override public long getBrowserPublishIntervalMillis() { return browserPublishIntervalMillis; }
    }

    private static final class DashboardTimingRegistration implements AutoCloseable {
        private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        private final ObjectName name;
        private DashboardTimingRegistration(long poll, long browser) throws Exception {
            name = new ObjectName(DASHBOARD_TIMING_MBEAN);
            server.registerMBean(new StandardMBean(
                    new DashboardTiming(poll, browser), DashboardTimingMXBean.class, true), name);
        }
        @Override public void close() throws Exception {
            if (server.isRegistered(name)) server.unregisterMBean(name);
        }
    }
}
