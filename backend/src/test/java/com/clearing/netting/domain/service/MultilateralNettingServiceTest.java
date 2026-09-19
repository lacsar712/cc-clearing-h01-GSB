package com.clearing.netting.domain.service;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultilateralNettingServiceTest {

    private MultilateralNettingService service;
    private Member a;
    private Member b;
    private Member c;
    private LocalDate settleDate;

    @BeforeEach
    void setUp() {
        service = new MultilateralNettingService();
        a = new Member("A", "Bank A", MemberStatus.ACTIVE);
        b = new Member("B", "Bank B", MemberStatus.ACTIVE);
        c = new Member("C", "Bank C", MemberStatus.ACTIVE);
        settleDate = LocalDate.of(2026, 9, 10);
    }

    @Test
    void conservationHoldsForTriangle() {
        List<TradeObligation> opens = List.of(
                obligation("A", "B", "100"),
                obligation("B", "C", "60"),
                obligation("C", "A", "40")
        );
        List<NetPosition> positions = service.net("run-1", "USD", opens, Map.of("A", a, "B", b, "C", c));

        BigDecimal sum = positions.stream().map(NetPosition::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO));
        assertEquals(3, positions.size());

        // Direction semantics: positive = receivable, negative = payable.
        // A pays 100, receives 40 -> -60; B receives 100, pays 60 -> +40; C receives 60, pays 40 -> +20.
        Map<String, BigDecimal> byMember = positions.stream().collect(
                java.util.stream.Collectors.toMap(NetPosition::getMemberId, NetPosition::getNetAmount));
        assertEquals(0, byMember.get("A").compareTo(new BigDecimal("-60.00000000")));
        assertEquals(0, byMember.get("B").compareTo(new BigDecimal("40.00000000")));
        assertEquals(0, byMember.get("C").compareTo(new BigDecimal("20.00000000")));
    }

    @Test
    void payerIsNegativePayeeIsPositive() {
        List<TradeObligation> opens = List.of(obligation("A", "B", "100"));
        List<NetPosition> positions = service.net("run-1b", "USD", opens, Map.of("A", a, "B", b));

        Map<String, BigDecimal> byMember = positions.stream().collect(
                java.util.stream.Collectors.toMap(NetPosition::getMemberId, NetPosition::getNetAmount));
        assertEquals(0, byMember.get("A").compareTo(new BigDecimal("-100.00000000")),
                "payer must be negative (payable)");
        assertEquals(0, byMember.get("B").compareTo(new BigDecimal("100.00000000")),
                "payee must be positive (receivable)");
    }

    @Test
    void rejectsSuspendedMember() {
        Member suspended = new Member("B", "Bank B", MemberStatus.SUSPENDED);
        List<TradeObligation> opens = List.of(obligation("A", "B", "10"));

        DomainException ex = assertThrows(DomainException.class, () ->
                service.net("run-2", "USD", opens, Map.of("A", a, "B", suspended)));
        assertEquals("SUSPENDED_MEMBER", ex.getCode());
        assertTrue(ex.getMessage().contains("B"));
    }

    @Test
    void rejectsMixedCurrency() {
        TradeObligation usd = obligation("A", "B", "10");
        TradeObligation eur = new TradeObligation(
                "o2", "B", "A", "EUR", new BigDecimal("5"),
                settleDate.minusDays(1), settleDate, ObligationStatus.OPEN, null);

        DomainException ex = assertThrows(DomainException.class, () ->
                service.net("run-3", "USD", List.of(usd, eur), Map.of("A", a, "B", b)));
        assertEquals("MIXED_CURRENCY", ex.getCode());
    }

    private TradeObligation obligation(String payer, String payee, String amount) {
        return new TradeObligation(
                java.util.UUID.randomUUID().toString(),
                payer,
                payee,
                "USD",
                new BigDecimal(amount),
                settleDate.minusDays(1),
                settleDate,
                ObligationStatus.OPEN,
                null);
    }
}
