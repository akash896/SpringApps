package com.example.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PricingApplicationTest {
    @Test void quoteContainsAUsablePrice() {
        QuoteResponse quote = new PricingController().quote("BOOK-1");
        assertThat(quote.available()).isTrue();
        assertThat(quote.unitPrice()).isPositive();
    }
}
