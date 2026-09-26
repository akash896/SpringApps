package com.example.order;

import io.micrometer.core.instrument.Clock;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }
    @Bean JmxMeterRegistry jmxMeterRegistry() { return new JmxMeterRegistry(JmxConfig.DEFAULT, Clock.SYSTEM); }
    @Bean RestClient.Builder restClientBuilder() { return RestClient.builder(); }
}
