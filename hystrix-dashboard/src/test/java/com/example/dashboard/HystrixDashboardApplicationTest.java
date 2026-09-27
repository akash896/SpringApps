package com.example.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HystrixDashboardApplicationTest {
    @Test
    void applicationClassIsAvailable() {
        assertThat(HystrixDashboardApplication.class).isNotNull();
    }
}
