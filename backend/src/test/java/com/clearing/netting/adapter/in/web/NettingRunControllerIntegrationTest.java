package com.clearing.netting.adapter.in.web;

import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Black-box operator flow over real HTTP (MockMvc + JWT):
 * login -> create obligations -> execute netting -> open run detail.
 * Runs two consecutive batches and verifies in the detail JSON that
 * payer positions are negative, payee positions positive, and ΣnetAmount = 0.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NettingRunControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MemberRepositoryPort memberRepository;

    private final LocalDate d1 = LocalDate.of(2026, 9, 21);
    private final LocalDate d2 = LocalDate.of(2026, 9, 22);

    @Test
    void operatorRunsNettingTwiceDetailShowsCorrectDirectionAndZeroSum() throws Exception {
        Member a = memberRepository.save(Member.create("HTTP Bank A"));
        Member b = memberRepository.save(Member.create("HTTP Bank B"));
        Member c = memberRepository.save(Member.create("HTTP Bank C"));

        String token = login();

        // ---- batch 1: A->B 100, B->C 60 (A net payer, C net payee) ----
        createObligation(token, a.getMemberId(), b.getMemberId(), "100", d1);
        createObligation(token, b.getMemberId(), c.getMemberId(), "60", d1);

        String run1Id = executeRun(token, d1);
        JsonNode detail1 = getDetail(token, run1Id);
        assertSign(detail1, a.getMemberId(), -1, "run1 payer A must be negative");
        assertSign(detail1, c.getMemberId(), 1, "run1 payee C must be positive");
        assertZeroSum(detail1);

        // ---- batch 2 (another settle date): C->A 30, B->A 10 (A net payee, C/B payers) ----
        createObligation(token, c.getMemberId(), a.getMemberId(), "30", d2);
        createObligation(token, b.getMemberId(), a.getMemberId(), "10", d2);

        String run2Id = executeRun(token, d2);
        JsonNode detail2 = getDetail(token, run2Id);
        assertSign(detail2, a.getMemberId(), 1, "run2 payee A must be positive");
        assertSign(detail2, b.getMemberId(), -1, "run2 payer B must be negative");
        assertSign(detail2, c.getMemberId(), -1, "run2 payer C must be negative");
        assertZeroSum(detail2);
    }

    private String login() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"operator\",\"password\":\"op123456\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private void createObligation(String token, String payer, String payee, String amount, LocalDate date) throws Exception {
        String body = String.format(
                "{\"payerMemberId\":\"%s\",\"payeeMemberId\":\"%s\",\"currency\":\"USD\",\"amount\":%s,\"tradeDate\":\"%s\",\"settleDate\":\"%s\"}",
                payer, payee, amount, date, date);
        mockMvc.perform(post("/api/obligations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private String executeRun(String token, LocalDate date) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/netting-runs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"settleDate\":\"%s\",\"currency\":\"USD\"}", date)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString());
        assertEquals("COMPLETED", json.path("run").path("status").asText());
        return json.path("run").path("runId").asText();
    }

    private JsonNode getDetail(String token, String runId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/netting-runs/" + runId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private void assertSign(JsonNode detail, String memberId, int expectedSign, String message) {
        JsonNode positions = detail.path("positions");
        for (JsonNode p : positions) {
            if (p.path("memberId").asText().equals(memberId)) {
                double net = p.path("netAmount").asDouble();
                assertEquals(expectedSign, (int) Math.signum(net), message + " (was " + net + ")");
                return;
            }
        }
        throw new AssertionError("member not found in detail positions: " + memberId);
    }

    private void assertZeroSum(JsonNode detail) {
        JsonNode positions = detail.path("positions");
        double sum = 0;
        for (JsonNode p : positions) {
            sum += p.path("netAmount").asDouble();
        }
        assertEquals(0.0, sum, 1e-9, "ΣnetAmount must be 0");
        assertEquals(0.0, detail.path("sumNetAmount").asDouble(), 1e-9, "detail.sumNetAmount must be 0");
    }
}
