package com.example.inventory;

import java.math.BigDecimal;

public record InventoryResponse(String sku, int quantityAvailable, BigDecimal unitPrice, String currency, boolean priceAvailable) { }
