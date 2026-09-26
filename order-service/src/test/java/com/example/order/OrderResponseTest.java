package com.example.order;

import static org.assertj.core.api.Assertions.assertThat;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OrderResponseTest {
    @Test void acceptedResponseIncludesAComputedTotal() {
        OrderResponse response = new OrderResponse("id", "BOOK-1", 2, new BigDecimal("25.00"), "USD", "ACCEPTED", "Order accepted");
        assertThat(response.total()).isEqualByComparingTo("25.00");
    }
}
