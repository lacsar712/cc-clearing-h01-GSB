package com.clearing.netting.application;

import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.NetPosition;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end netting run test through the application service and persistence
 * (the same data the run-detail page reads). Two consecutive runs must both:
 *   - finish COMPLETED with ΣnetAmount = 0
 *   - keep the business sign convention: payer (付款方) negative, payee (收款方) positive
 */
@SpringBootTest
class NettingRunIntegrationTest {

    @Autowired
    private NettingApplicationService nettingService;
    @Autowired
    private MemberRepositoryPort memberRepository;
    @Autowired
    private ObligationRepositoryPort obligationRepository;

    private static final LocalDate D1 = LocalDate.of(2026, 9, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 9, 11);

    @Test
    void twoConsecutiveRunsKeepPayDirectionAndConservation() {
        Member m1 = memberRepository.save(Member.create("Alpha Bank"));
        Member m2 = memberRepository.save(Member.create("Beta Securities"));
        Member m3 = memberRepository.save(Member.create("Gamma Clearing"));

        // ---- Run 1 (mirrors seed triangle) ----
        obligationRepository.save(TradeObligation.open(m1.getMemberId(), m2.getMemberId(), "USD", new BigDecimal("100000"), D1, D1));
        obligationRepository.save(TradeObligation.open(m2.getMemberId(), m3.getMemberId(), "USD", new BigDecimal("60000"), D1, D1));
        obligationRepository.save(TradeObligation.open(m3.getMemberId(), m1.getMemberId(), "USD", new BigDecimal("40000"), D1, D1));
        obligationRepository.save(TradeObligation.open(m1.getMemberId(), m3.getMemberId(), "USD", new BigDecimal("25000"), D1, D1));

        NettingApplicationService.NettingRunResult r1 = nettingService.execute(D1, "USD");
        String run1Id = r1.run().getRunId();
        assertEquals(NettingRunStatus.COMPLETED, r1.run().getStatus());

        Map<String, BigDecimal> net1 = assertPositions(run1Id);
        // M1: pays 125000, receives 40000 -> -85000 (net payer)
        // M2: receives 100000, pays 60000 -> +40000 (net payee)
        // M3: receives 85000, pays 40000 -> +45000 (net payee)
        assertEquals(0, net1.get(m1.getMemberId()).compareTo(new BigDecimal("-85000")),
                "run1: net payer M1 must be negative");
        assertTrue(net1.get(m2.getMemberId()).compareTo(new BigDecimal("40000")) == 0
                && net1.get(m2.getMemberId()).signum() > 0,
                "run1: net payee M2 must be positive");
        assertEquals(0, net1.get(m3.getMemberId()).compareTo(new BigDecimal("45000")),
                "run1: net payee M3 must be positive");

        // obligations consumed by run 1 are NETTED
        for (TradeObligation o : nettingService.getRunObligations(run1Id)) {
            assertEquals(ObligationStatus.NETTED, o.getStatus(),
                    "obligation " + o.getObligationId() + " should be NETTED");
        }
        nettingService.settle(run1Id);
        for (TradeObligation o : nettingService.getRunObligations(run1Id)) {
            assertEquals(ObligationStatus.SETTLED, o.getStatus());
        }

        // ---- Run 2: fresh OPEN obligations on another settle date ----
        obligationRepository.save(TradeObligation.open(m2.getMemberId(), m1.getMemberId(), "USD", new BigDecimal("5000"), D2, D2));
        obligationRepository.save(TradeObligation.open(m1.getMemberId(), m3.getMemberId(), "USD", new BigDecimal("7000"), D2, D2));

        NettingApplicationService.NettingRunResult r2 = nettingService.execute(D2, "USD");
        String run2Id = r2.run().getRunId();
        assertEquals(NettingRunStatus.COMPLETED, r2.run().getStatus());

        Map<String, BigDecimal> net2 = assertPositions(run2Id);
        // M1: receives 5000, pays 7000 -> -2000 (net payer)
        // M2: pays 5000               -> -5000 (payer)
        // M3: receives 7000           -> +7000 (payee)
        assertEquals(0, net2.get(m1.getMemberId()).compareTo(new BigDecimal("-2000")),
                "run2: net payer M1 must be negative");
        assertEquals(0, net2.get(m2.getMemberId()).compareTo(new BigDecimal("-5000")),
                "run2: payer M2 must be negative");
        assertEquals(0, net2.get(m3.getMemberId()).compareTo(new BigDecimal("7000")),
                "run2: payee M3 must be positive");
    }

    /** Reads positions exactly like the run-detail endpoint and verifies Σ = 0. */
    private Map<String, BigDecimal> assertPositions(String runId) {
        NettingRun run = nettingService.getRun(runId);
        assertEquals(NettingRunStatus.COMPLETED, run.getStatus());

        List<NetPosition> positions = nettingService.getPositions(runId);
        Map<String, BigDecimal> netByMember = new HashMap<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (NetPosition p : positions) {
            netByMember.put(p.getMemberId(), p.getNetAmount());
            sum = sum.add(p.getNetAmount());
        }
        assertEquals(0, sum.compareTo(BigDecimal.ZERO), "ΣnetAmount must be 0 for run " + runId);
        return netByMember;
    }
}
