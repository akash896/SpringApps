package com.example.order;

import java.math.BigDecimal;

record InventorySnapshot(String sku, int quantityAvailable, BigDecimal unitPrice, String currency, boolean priceAvailable) { }
