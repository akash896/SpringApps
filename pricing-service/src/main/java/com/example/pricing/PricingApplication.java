package com.example.pricing;

import io.micrometer.core.instrument.Clock;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class PricingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PricingApplication.class, args);
    }

    /** Publishes every Micrometer meter, including Resilience4j meters, as JMX MBeans. */
    @Bean
    JmxMeterRegistry jmxMeterRegistry() {
        return new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM);
    }
}
