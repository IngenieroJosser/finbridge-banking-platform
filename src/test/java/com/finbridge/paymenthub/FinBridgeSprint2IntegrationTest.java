package com.finbridge.paymenthub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@SpringBootTest(properties = {
        "finbridge.provider.worker-enabled=false",
        "finbridge.sandbox.funding-enabled=true",
        "spring.datasource.hikari.maximum-pool-size=26"
})
class FinBridgeSprint2IntegrationTest {
    private static final String CUSTOMER_A = "00000000-0000-4000-8000-000000000011";
    private static final String CUSTOMER_B = "00000000-0000-4000-8000-000000000012";
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finbridge_sprint2_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        TestJwt.properties(registry);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void fb004_at01_transferencia_25000_conserva_el_dinero_y_crea_ledger_auditable() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        fund(a, "100000.00");

        MvcResult result = transfer(a, b, "25000.00", "once-" + UUID.randomUUID(),
                "Pago de prueba", 201);
        JsonNode response = body(result);
        UUID transferId = UUID.fromString(response.get("id").asText());
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/transfers/" + transferId);
        assertThat(response.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.get("type").asText()).isEqualTo("INTERNAL");
        assertAmount(a, "75000.00");
        assertAmount(b, "25000.00");
        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("100000.00");

        UUID journalId = jdbc.queryForObject("SELECT id FROM journal WHERE transfer_id=?",
                UUID.class, transferId);
        assertThat(journalId).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM journal WHERE id=?", String.class, journalId))
                .isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE journal_id=?",
                Long.class, journalId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM posting WHERE journal_id=? AND side='DEBIT'",
                BigDecimal.class, journalId)).isEqualByComparingTo("25000.00");
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM posting WHERE journal_id=? AND side='CREDIT'",
                BigDecimal.class, journalId)).isEqualByComparingTo("25000.00");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE aggregate_id=? AND event_type='INTERNAL_TRANSFER_COMPLETED'
                """, Long.class, transferId)).isEqualTo(1L);
        mvc.perform(get("/api/v1/transfers/{id}", transferId).header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(transferId.toString()));
        mvc.perform(get("/api/v1/transfers/{id}", UUID.randomUUID()).header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void fb004_at02_at03_at04_at05_rechazos_y_rollback_sin_mutaciones() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        UUID blocked = account(CUSTOMER_A);
        UUID otherOwner = account(CUSTOMER_B);
        fund(a, "100000.00");
        jdbc.update("UPDATE account SET status='BLOCKED' WHERE id=?", blocked);

        transfer(a, b, "0", "zero-" + UUID.randomUUID(), "Cero", 400);
        transfer(a, b, "-10.00", "negative-" + UUID.randomUUID(), "Negativo", 400);
        transfer(a, b, "1.234", "fractions-" + UUID.randomUUID(), "Inexacto", 400);
        transfer(a, b, "120000.00", "insufficient-" + UUID.randomUUID(), "Insuficiente", 422);
        transfer(a, b, "50000001.00", "limit-" + UUID.randomUUID(), "Máximo", 422);
        transfer(a, a, "1000.00", "same-" + UUID.randomUUID(), "Mismo", 422);
        transfer(a, blocked, "1000.00", "blocked-" + UUID.randomUUID(), "Bloqueada", 422);
        transfer(a, UUID.randomUUID(), "1000.00", "missing-" + UUID.randomUUID(), "No existe", 404);
        transfer(a, otherOwner, "1000.00", "owner-" + UUID.randomUUID(), "Titular distinto", 403);
        transferWithCurrency(a, b, "1000.00", "USD", "currency-" + UUID.randomUUID(), 422);
        transfer(a, b, "1.00", "short", "Clave corta", 400);
        mvc.perform(post("/api/v1/transfers").header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":\"" + a + "\",\"amount\":100,\"currency\":\"COP\"}"))
                .andExpect(status().isBadRequest());

        assertAmount(a, "100000.00");
        assertAmount(b, "0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE source_id=?",
                Long.class, a)).isZero();
    }

    @Test
    void fb005_at06_at07_replay_y_conflicto_de_cuerpo() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        fund(a, "100000.00");
        String key = "replay-" + UUID.randomUUID();
        MvcResult first = transfer(a, b, "25000.0", key, "  Nómina  ", 201);
        UUID id = UUID.fromString(body(first).get("id").asText());
        for (int i = 0; i < 10; i++) {
            MvcResult repeated = transfer(a, b, "25000.00", key, "Nómina", 201);
            assertThat(repeated.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
        }
        transfer(a, b, "26000.00", key, "Nómina", 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE id=?", Long.class, id))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM idempotency_record
                WHERE operation='INTERNAL_TRANSFER' AND resource_id=?
                """, Long.class, id)).isEqualTo(1L);
        assertAmount(a, "75000.00");
        assertAmount(b, "25000.00");
    }

    @Test
    void fb005_veinte_peticiones_concurrentes_una_clave_un_transfer_y_un_journal() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        fund(a, "100000.00");
        String key = "20-at-once-" + UUID.randomUUID();
        List<MvcResult> results = parallel(20,
                () -> transfer(a, b, "30000.00", key, "Carga simultánea", 201));

        Set<String> ids = new HashSet<>();
        for (MvcResult result : results) {
            ids.add(body(result).get("id").asText());
            assertThat(result.getResponse().getHeader("Location"))
                    .isEqualTo("/api/v1/transfers/" + body(result).get("id").asText());
        }
        assertThat(ids).hasSize(1);
        UUID transferId = UUID.fromString(ids.iterator().next());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal WHERE transfer_id=?",
                Long.class, transferId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE journal_id = "
                + "(SELECT id FROM journal WHERE transfer_id=?)", Long.class, transferId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE resource_id=?",
                Long.class, transferId)).isEqualTo(1L);
        assertAmount(a, "70000.00");
        assertAmount(b, "30000.00");
    }

    @Test
    void fb006_at08_dos_debitos_simultaneos_de_80000_solo_un_exito() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        UUID c = account(CUSTOMER_A);
        fund(a, "100000.00");

        List<MvcResult> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<MvcResult> toB = () -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return transferNoExpectation(a, b, "80000.00", "debit-b-" + UUID.randomUUID());
            };
            Callable<MvcResult> toC = () -> {
                ready.countDown();
                if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                return transferNoExpectation(a, c, "80000.00", "debit-c-" + UUID.randomUUID());
            };
            Future<MvcResult> first = pool.submit(toB);
            Future<MvcResult> second = pool.submit(toC);
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            results = List.of(first.get(90, TimeUnit.SECONDS), second.get(90, TimeUnit.SECONDS));
        }
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 201).count())
                .isEqualTo(1L);
        assertThat(results.stream().filter(r -> r.getResponse().getStatus() == 422).count())
                .isEqualTo(1L);
        assertAmount(a, "20000.00");
        assertThat(balance(b).add(balance(c))).isEqualByComparingTo("80000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE source_id=?",
                Long.class, a)).isEqualTo(1L);
    }

    @Test
    void fb006_at09_a_b_y_b_a_concurrentes_no_generan_deadlocks() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        fund(a, "100000.00");
        fund(b, "100000.00");
        List<Callable<MvcResult>> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            requests.add(() -> transferNoExpectation(a, b, "10000.00", "a-to-b-" + UUID.randomUUID()));
            requests.add(() -> transferNoExpectation(b, a, "10000.00", "b-to-a-" + UUID.randomUUID()));
        }
        List<MvcResult> results = parallelTasks(requests);
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(201));
        assertAmount(a, "100000.00");
        assertAmount(b, "100000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE source_id IN (?,?)",
                Long.class, a, b)).isEqualTo(16L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (
                  SELECT j.id FROM journal j LEFT JOIN posting p ON p.journal_id=j.id
                  WHERE j.journal_type='INTERNAL_TRANSFER' AND j.transfer_id IN
                    (SELECT id FROM transfer WHERE source_id IN (?,?))
                  GROUP BY j.id HAVING count(p.id) <> 2
                ) inconsistent
                """, Long.class, a, b)).isZero();
        assertLedgerMatchesBalance(a);
        assertLedgerMatchesBalance(b);
    }

    @Test
    void fb004_v4_impide_confirmar_transfer_sin_ledger_y_editar_transfer_confirmada() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        fund(a, "100000.00");
        UUID orphanTransfer = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO transfer(id,source_id,destination_id,amount,currency,transfer_type,status)
                VALUES(?,?,?,5000.00,'COP','INTERNAL','COMPLETED')
                """, orphanTransfer, a, b))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE id=?",
                Long.class, orphanTransfer)).isZero();

        UUID confirmed = UUID.fromString(body(transfer(a, b, "10000.00",
                "immutable-transfer-" + UUID.randomUUID(), "No modificar", 201))
                .get("id").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE transfer SET reference='hack' WHERE id=?", confirmed))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfer WHERE id=?", confirmed))
                .isInstanceOf(RuntimeException.class);
        assertAmount(a, "90000.00");
        assertAmount(b, "10000.00");
    }

    @Test
    void fb006_at10_un_error_despues_del_primer_posting_revierte_todo() throws Exception {
        UUID a = account(CUSTOMER_A);
        UUID b = account(CUSTOMER_A);
        fund(a, "100000.00");
        UUID transferId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO transfer(id,source_id,destination_id,amount,currency,transfer_type,status)
                    VALUES(?,?,?,10000.00,'COP','INTERNAL','COMPLETED')
                    """, transferId, a, b);
            jdbc.update("""
                    INSERT INTO journal(id,transfer_id,journal_type,currency,status)
                    VALUES(?,?,'INTERNAL_TRANSFER','COP','DRAFT')
                    """, journalId, transferId);
            jdbc.update("""
                    INSERT INTO posting(id,journal_id,account_id,side,amount,currency)
                    VALUES(?,?,?,'DEBIT',10000.00,'COP')
                    """, UUID.randomUUID(), journalId, a);
            // Se simula un crash antes del segundo posting y de POSTED.
        })).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE id=?", Long.class, transferId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal WHERE id=?", Long.class, journalId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE journal_id=?", Long.class, journalId))
                .isZero();
        assertAmount(a, "100000.00");
        assertAmount(b, "0.00");
    }

    private UUID account(String customerId) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/accounts").header("Authorization", TestJwt.bearer(TestJwt.OPS,"OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"currency\":\"COP\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private void fund(UUID accountId, String amount) throws Exception {
        mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", accountId)
                        .header("Authorization", TestJwt.bearer(TestJwt.ADMIN,"ADMIN"))
                        .header("Idempotency-Key", "fund-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + ",\"reason\":\"Fondeo synthetic test\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult transfer(UUID source, UUID target, String amount, String key,
                               String reference, int expectedStatus) throws Exception {
        return mvc.perform(post("/api/v1/transfers").header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER"))
                        .header("Idempotency-Key", key)
                        .header("X-Correlation-ID", "sprint2-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, target, amount, "COP", reference)))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private MvcResult transferNoExpectation(UUID source, UUID target,
                                            String amount, String key) throws Exception {
        return mvc.perform(post("/api/v1/transfers").header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, target, amount, "COP", "Stress test")))
                .andReturn();
    }

    private void transferWithCurrency(UUID source, UUID target, String amount,
                                      String currency, String key, int expectedStatus) throws Exception {
        mvc.perform(post("/api/v1/transfers").header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER"))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(source, target, amount, currency, "Divisa")))
                .andExpect(status().is(expectedStatus));
    }

    private String transferJson(UUID source, UUID target, String amount, String currency, String reference) {
        return "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + target
                + "\",\"amount\":" + amount + ",\"currency\":\"" + currency
                + "\",\"reference\":\"" + reference + "\"}";
    }

    private <T> List<T> parallel(int count, Callable<T> action) throws Exception {
        List<Callable<T>> tasks = new ArrayList<>();
        for (int i = 0; i < count; i++) tasks.add(action);
        return parallelTasks(tasks);
    }

    private <T> List<T> parallelTasks(List<Callable<T>> tasks) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de inicio");
                    return task.call();
                }));
            }
            assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(120, TimeUnit.SECONDS));
            return results;
        }
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private BigDecimal balance(UUID account) {
        return jdbc.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",
                BigDecimal.class, account);
    }

    private void assertAmount(UUID account, String expected) {
        assertThat(balance(account)).isEqualByComparingTo(expected);
    }

    private void assertLedgerMatchesBalance(UUID account) {
        BigDecimal fromPostings = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN side='CREDIT' THEN amount ELSE -amount END),0)
                FROM posting WHERE account_id=?
                """, BigDecimal.class, account);
        assertThat(balance(account)).isEqualByComparingTo(fromPostings);
        assertThat(balance(account)).isGreaterThanOrEqualTo(ZERO);
    }
}
