package com.example.dashboard;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class StreamProxyController {
    private final Map<String, String> streamUrls;
    private final ExecutorService executor;

    public StreamProxyController(
            @Value("${dashboard.services.pricing-url}") String pricingUrl,
            @Value("${dashboard.services.inventory-url}") String inventoryUrl,
            @Value("${dashboard.services.order-url}") String orderUrl) {
        this.streamUrls = Map.of(
                "pricing", withStreamPath(pricingUrl),
                "inventory", withStreamPath(inventoryUrl),
                "order", withStreamPath(orderUrl));
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "hystrix-dashboard-stream-proxy");
            thread.setDaemon(true);
            return thread;
        });
    }

    @GetMapping(path = "/streams/{service}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String service) {
        String streamUrl = streamUrls.get(service);
        if (streamUrl == null) {
            throw new IllegalArgumentException("Unknown service stream: " + service);
        }

        SseEmitter emitter = new SseEmitter(0L);
        executor.submit(() -> proxy(streamUrl, emitter));
        return emitter;
    }

    private void proxy(String streamUrl, SseEmitter emitter) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(streamUrl).toURL().openConnection();
            connection.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
            connection.setReadTimeout(0);
            connection.setRequestProperty("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        emitter.send(line.substring("data:".length()).trim());
                    } else if (!line.isBlank() && !line.startsWith(":")) {
                        emitter.send(line);
                    }
                }
            }
            emitter.complete();
        } catch (IOException error) {
            emitter.completeWithError(error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String withStreamPath(String baseUrl) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/hystrix.stream";
    }
}
