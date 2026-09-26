package com.example.inventory;

import java.math.BigDecimal;

record PriceQuote(String sku, BigDecimal unitPrice, String currency, boolean available) {
}
