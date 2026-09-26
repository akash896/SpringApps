package com.example.pricing;

import java.math.BigDecimal;

public record QuoteResponse(
        String sku,
        BigDecimal unitPrice,
        String currency,
        boolean available) {
}
