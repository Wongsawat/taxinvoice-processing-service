package com.wpanther.taxinvoice.processing.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Money value object
 */
class MoneyTest {

    @Test
    void testCreateMoneyWithBigDecimal() {
        // Given
        BigDecimal amount = new BigDecimal("100.50");
        String currency = "THB";

        // When
        Money money = Money.of(amount, currency);

        // Then
        assertNotNull(money);
        assertEquals(new BigDecimal("100.50"), money.amount());
        assertEquals("THB", money.currency());
    }

    @Test
    void testCreateZeroMoney() {
        // Given
        String currency = "EUR";

        // When
        Money money = Money.zero(currency);

        // Then
        assertNotNull(money);
        assertEquals(BigDecimal.ZERO.setScale(2), money.amount());
        assertEquals("EUR", money.currency());
        assertTrue(money.isZero());
    }

    @Test
    void testAddMoney() {
        // Given
        Money money1 = Money.of(new BigDecimal("100.50"), "THB");
        Money money2 = Money.of(new BigDecimal("50.25"), "THB");

        // When
        Money result = money1.add(money2);

        // Then
        assertEquals(new BigDecimal("150.75"), result.amount());
        assertEquals("THB", result.currency());
    }

    @Test
    void testAddMoneyWithDifferentCurrency() {
        // Given
        Money money1 = Money.of(new BigDecimal("100.00"), "THB");
        Money money2 = Money.of(new BigDecimal("50.00"), "USD");

        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> money1.add(money2));
        assertTrue(exception.getMessage().contains("Currency mismatch"));
    }

    @Test
    void testSubtractMoney() {
        // Given
        Money money1 = Money.of(new BigDecimal("100.50"), "THB");
        Money money2 = Money.of(new BigDecimal("50.25"), "THB");

        // When
        Money result = money1.subtract(money2);

        // Then
        assertEquals(new BigDecimal("50.25"), result.amount());
        assertEquals("THB", result.currency());
    }

    @Test
    void testSubtractMoneyWithDifferentCurrency() {
        // Given
        Money money1 = Money.of(new BigDecimal("100.00"), "THB");
        Money money2 = Money.of(new BigDecimal("50.00"), "USD");

        // When/Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> money1.subtract(money2));
        assertTrue(exception.getMessage().contains("Currency mismatch"));
    }

    @Test
    void testMultiplyByBigDecimal() {
        // Given
        Money money = Money.of(new BigDecimal("100.00"), "THB");
        BigDecimal factor = new BigDecimal("1.5");

        // When
        Money result = money.multiply(factor);

        // Then
        assertEquals(new BigDecimal("150.00"), result.amount());
        assertEquals("THB", result.currency());
    }

    @Test
    void testMultiplyByDecimalFactor() {
        // Given
        Money money = Money.of(new BigDecimal("100.00"), "THB");
        BigDecimal factor = new BigDecimal("2.5");

        // When
        Money result = money.multiply(factor);

        // Then
        assertEquals(new BigDecimal("250.00"), result.amount());
        assertEquals("THB", result.currency());
    }

    @Test
    void testMultiplyByInt() {
        // Given
        Money money = Money.of(new BigDecimal("1000.00"), "THB");

        // When
        Money result = money.multiply(10);

        // Then
        assertEquals(new BigDecimal("10000.00"), result.amount());
        assertEquals("THB", result.currency());
    }

    @Test
    void testMultiplyByIntUsesExactArithmetic() {
        // multiply(int) must route through BigDecimal, not double.
        // 0.1 * 3 via double = 0.30000000000000004; via BigDecimal.valueOf = 0.3 exactly.
        Money money = Money.of(new BigDecimal("0.10"), "THB");

        Money result = money.multiply(3);

        // BigDecimal path: 0.10 * 3 = 0.30 (exact)
        assertEquals(new BigDecimal("0.30"), result.amount());
    }

    @Test
    void testMultiplyWithNull() {
        // Given
        Money money = Money.of(new BigDecimal("100.00"), "THB");

        // When/Then
        assertThrows(NullPointerException.class, () -> money.multiply((BigDecimal) null));
    }

    @Test
    void testIsPositive() {
        // Given
        Money positiveMoney = Money.of(new BigDecimal("100.00"), "THB");
        Money negativeMoney = Money.of(new BigDecimal("-100.00"), "THB");
        Money zeroMoney = Money.zero("THB");

        // When/Then
        assertTrue(positiveMoney.isPositive());
        assertFalse(negativeMoney.isPositive());
        assertFalse(zeroMoney.isPositive());
    }

    @Test
    void testIsNegative() {
        // Given
        Money positiveMoney = Money.of(new BigDecimal("100.00"), "THB");
        Money negativeMoney = Money.of(new BigDecimal("-100.00"), "THB");
        Money zeroMoney = Money.zero("THB");

        // When/Then
        assertFalse(positiveMoney.isNegative());
        assertTrue(negativeMoney.isNegative());
        assertFalse(zeroMoney.isNegative());
    }

    @Test
    void testIsZero() {
        // Given
        Money positiveMoney = Money.of(new BigDecimal("100.00"), "THB");
        Money zeroMoney = Money.zero("THB");

        // When/Then
        assertFalse(positiveMoney.isZero());
        assertTrue(zeroMoney.isZero());
    }

    @Test
    void testNullAmount() {
        // When/Then
        assertThrows(NullPointerException.class, () -> Money.of(null, "THB"));
    }

    @Test
    void testNullCurrency() {
        // When/Then
        assertThrows(NullPointerException.class, () -> Money.of(BigDecimal.TEN, null));
    }

    @Test
    void testInvalidCurrencyLength() {
        // Given
        BigDecimal amount = BigDecimal.TEN;

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> Money.of(amount, "US"));
        assertThrows(IllegalArgumentException.class, () -> Money.of(amount, "USDT"));
    }

    @Test
    void testAmountScaleNormalization() {
        // Given
        BigDecimal amount = new BigDecimal("100.123456");

        // When
        Money money = Money.of(amount, "THB");

        // Then
        assertEquals(2, money.amount().scale());
        assertEquals(new BigDecimal("100.12"), money.amount());
    }

    @Test
    void testToString() {
        // Given
        Money money = Money.of(new BigDecimal("100.50"), "THB");

        // When
        String result = money.toString();

        // Then
        assertEquals("100.50 THB", result);
    }

    @Test
    void testEquality() {
        // Given
        Money money1 = Money.of(new BigDecimal("100.50"), "THB");
        Money money2 = Money.of(new BigDecimal("100.50"), "THB");
        Money money3 = Money.of(new BigDecimal("100.51"), "THB");
        Money money4 = Money.of(new BigDecimal("100.50"), "USD");

        // When/Then
        assertEquals(money1, money2);
        assertNotEquals(money1, money3);
        assertNotEquals(money1, money4);
    }

    @Test
    void testHashCode() {
        // Given
        Money money1 = Money.of(new BigDecimal("100.50"), "THB");
        Money money2 = Money.of(new BigDecimal("100.50"), "THB");

        // When/Then
        assertEquals(money1.hashCode(), money2.hashCode());
    }
}
