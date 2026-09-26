package com.example.order;

import java.math.BigDecimal;

public record OrderResponse(String orderId, String sku, int quantity, BigDecimal total, String currency, String status, String message) { }
