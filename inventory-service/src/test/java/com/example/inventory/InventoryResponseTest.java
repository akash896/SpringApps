package com.example.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InventoryResponseTest {
    @Test void responseCarriesStockAndPriceInformation() {
        InventoryResponse response = new InventoryResponse("BOOK-1", 2, new BigDecimal("12.50"), "USD", true);
        assertThat(response.quantityAvailable()).isPositive();
        assertThat(response.priceAvailable()).isTrue();
    }
}
