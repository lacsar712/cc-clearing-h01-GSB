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
    }

    @Test
    void signFollowsPayDirectionPayableNegativeReceivablePositive() {
        // Business convention: payer (付款方) net = payable  -> negative
        //                      payee (收款方) net = receivable -> positive
        List<TradeObligation> opens = List.of(
                obligation("A", "B", "100"),
                obligation("B", "C", "60"),
                obligation("C", "A", "40")
        );
        // A: pays 100, receives 40 -> -60 (net payer)
        // B: receives 100, pays 60 -> +40 (net payee)
        // C: receives 60, pays 40  -> +20 (net payee)
        List<NetPosition> positions = service.net("run-1", "USD", opens, Map.of("A", a, "B", b, "C", c));
        Map<String, BigDecimal> netByMember = new java.util.HashMap<>();
        for (NetPosition p : positions) {
            netByMember.put(p.getMemberId(), p.getNetAmount());
        }

        assertEquals(0, netByMember.get("A").compareTo(new BigDecimal("-60")),
                "net payer A must be negative (payable)");
        assertEquals(0, netByMember.get("B").compareTo(new BigDecimal("40")),
                "net payee B must be positive (receivable)");
        assertEquals(0, netByMember.get("C").compareTo(new BigDecimal("20")),
                "net payee C must be positive (receivable)");

        BigDecimal sum = netByMember.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "ΣnetAmount must stay 0");
    }

    @Test
    void singleObligationPayerNegativePayeePositive() {
        List<TradeObligation> opens = List.of(obligation("A", "B", "10"));
        List<NetPosition> positions = service.net("run-4", "USD", opens, Map.of("A", a, "B", b));
        Map<String, BigDecimal> netByMember = new java.util.HashMap<>();
        for (NetPosition p : positions) {
            netByMember.put(p.getMemberId(), p.getNetAmount());
        }

        assertEquals(0, netByMember.get("A").compareTo(new BigDecimal("-10")),
                "payer A must be negative (payable)");
        assertEquals(0, netByMember.get("B").compareTo(new BigDecimal("10")),
                "payee B must be positive (receivable)");
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
