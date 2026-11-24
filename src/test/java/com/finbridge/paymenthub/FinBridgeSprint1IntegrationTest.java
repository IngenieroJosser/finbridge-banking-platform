package com.finbridge.paymenthub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@SpringBootTest(properties = {
        "finbridge.provider.worker-enabled=false",
        "finbridge.sandbox.funding-enabled=true"
})
class FinBridgeSprint1IntegrationTest {
    private static final String CUSTOMER_A = "00000000-0000-4000-8000-000000000011";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("finbridge_test")
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
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void fb001_migraciones_entidades_jpa_y_datos_base_reproducibles() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version IN ('1','2','3') AND success
                """, Integer.class);
        assertThat(n).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT available_amount FROM account_balance
                WHERE account_id='00000000-0000-4000-8000-000000000002'
                """, BigDecimal.class)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void fb002_cuenta_cop_saldo_cero_y_respuestas_http() throws Exception {
        UUID account = openAccount();
        mvc.perform(get("/api/v1/accounts/{id}/balance", account).header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(account.toString()))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.availableBalance").value(0.0));
        mvc.perform(get("/api/v1/accounts/{id}/balance", UUID.randomUUID()).header("Authorization", TestJwt.bearer(TestJwt.A,"CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/accounts").header("Authorization", TestJwt.bearer(TestJwt.OPS,"OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + CUSTOMER_A + "\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/accounts").header("Authorization", TestJwt.bearer(TestJwt.OPS,"OPERATIONS")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"COP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fb003_fondeo_balanceado_auditable_con_idempotencia() throws Exception {
        UUID account = openAccount();
        String key = "opening-" + UUID.randomUUID();
        MvcResult first = fund(account, key, "100000.00", "Fondeo inicial sintético").andReturn();
        UUID journalId = UUID.fromString(json.readTree(first.getResponse().getContentAsString())
                .get("journalId").asText());
        assertThat(balance(account)).isEqualByComparingTo("100000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE journal_id=?",
                Long.class, journalId)).isEqualTo(2L);
        BigDecimal debits = jdbc.queryForObject("""
                SELECT sum(amount) FROM posting WHERE journal_id=? AND side='DEBIT'
                """, BigDecimal.class, journalId);
        BigDecimal credits = jdbc.queryForObject("""
                SELECT sum(amount) FROM posting WHERE journal_id=? AND side='CREDIT'
                """, BigDecimal.class, journalId);
        assertThat(debits).isEqualByComparingTo(credits);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_event WHERE aggregate_id=? AND event_type='OPENING_BALANCE_POSTED'
                """, Long.class, journalId)).isEqualTo(1L);

        // Replay autorizado: mismo recurso, ningún asiento adicional.
        UUID replayedJournal = UUID.fromString(json.readTree(fund(account, key,
                "100000.00", "Fondeo inicial sintético").andReturn()
                .getResponse().getContentAsString()).get("journalId").asText());
        assertThat(replayedJournal).isEqualTo(journalId);
        assertThat(balance(account)).isEqualByComparingTo("100000.00");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM journal WHERE opening_account_id=?
                """, Long.class, account)).isEqualTo(1L);

        mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", account)
                .header("Authorization", TestJwt.bearer(TestJwt.ADMIN,"ADMIN")).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":200000.00,\"reason\":\"Fondeo inicial sintético\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", account)
                .header("Authorization", TestJwt.bearer(TestJwt.ADMIN,"ADMIN"))
                .header("Idempotency-Key", "another-key-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":100000.00,\"reason\":\"Fondeo repetido simulado\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void fb003_sin_token_y_amount_invalido_no_mutan_el_saldo() throws Exception {
        UUID account = openAccount();
        mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", account)
                .header("Idempotency-Key", "missing-token-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000.00,\"reason\":\"Fondeo ficticio\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", account)
                .header("Authorization", TestJwt.bearer(TestJwt.ADMIN,"ADMIN"))
                .header("Idempotency-Key", "invalid-amount-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":-5.00,\"reason\":\"Fondeo ficticio\"}"))
                .andExpect(status().isBadRequest());
        assertThat(balance(account)).isEqualByComparingTo("0.00");
    }

    @Test
    void fb001_un_journal_sin_postings_jamas_puede_confirmarse() {
        UUID invalidJournal = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO journal(id, journal_type,opening_account_id,currency,status)
                VALUES(?, 'OPENING_FUNDING', '00000000-0000-4000-8000-000000000002','COP','DRAFT')
                """, invalidJournal))).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal WHERE id=?",
                Long.class, invalidJournal)).isZero();
    }

    @Test
    void fb001_dos_postings_desbalanceados_hacen_rollback_del_saldo() throws Exception {
        UUID account = openAccount();
        UUID invalidJournal = UUID.randomUUID();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO journal(id, journal_type,opening_account_id,currency,status)
                    VALUES(?, 'OPENING_FUNDING', ?, 'COP', 'DRAFT')
                    """, invalidJournal, account);
            jdbc.update("""
                    INSERT INTO posting(id,journal_id,account_id,side,amount,currency)
                    VALUES(?,?,?,'DEBIT',300.00,'COP')
                    """, UUID.randomUUID(), invalidJournal,
                    UUID.fromString("00000000-0000-4000-8000-000000000002"));
            jdbc.update("""
                    INSERT INTO posting(id,journal_id,account_id,side,amount,currency)
                    VALUES(?,?,?,'CREDIT',200.00,'COP')
                    """, UUID.randomUUID(), invalidJournal, account);
            jdbc.update("UPDATE journal SET status='POSTED', posted_at=now() WHERE id=?", invalidJournal);
        })).isInstanceOf(RuntimeException.class);
        assertThat(balance(account)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal WHERE id=?",
                Long.class, invalidJournal)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM posting WHERE journal_id=?",
                Long.class, invalidJournal)).isZero();
    }

    @Test
    void fb001_no_se_puede_editar_posting_ni_saldo_manualmente() throws Exception {
        UUID account = openAccount();
        UUID journalId = UUID.fromString(json.readTree(fund(account,
                "immutable-" + UUID.randomUUID(), "2500.00", "Fondeo de inmutabilidad")
                .andReturn().getResponse().getContentAsString()).get("journalId").asText());
        UUID postingId = jdbc.queryForObject("SELECT id FROM posting WHERE journal_id=? LIMIT 1",
                UUID.class, journalId);
        assertThatThrownBy(() -> jdbc.update("UPDATE posting SET amount=999 WHERE id=?", postingId))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE account_balance SET available_amount=999 WHERE account_id=?", account))
                .isInstanceOf(RuntimeException.class);
        assertThat(balance(account)).isEqualByComparingTo("2500.00");
    }

    @Test
    void fb003_dos_reintentos_concurrentes_igual_clave_un_journal() throws Exception {
        UUID account = openAccount();
        String key = "concurrent-same-key-" + UUID.randomUUID();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<UUID> task = () -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de inicio");
                return UUID.fromString(json.readTree(fund(account, key, "7000.00", "Fondeo simultáneo de prueba")
                        .andReturn().getResponse().getContentAsString()).get("journalId").asText());
            };
            Future<UUID> one = pool.submit(task);
            Future<UUID> two = pool.submit(task);
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(one.get(30, TimeUnit.SECONDS)).isEqualTo(two.get(30, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal WHERE opening_account_id=?",
                Long.class, account)).isEqualTo(1L);
        assertThat(balance(account)).isEqualByComparingTo("7000.00");
    }

    @Test
    void fb003_dos_fondeos_con_distinta_clave_en_carrera_solo_un_ganador() throws Exception {
        UUID account = openAccount();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> task = () -> {
                ready.countDown();
                if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("timeout de inicio");
                int status = mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", account)
                        .header("Authorization", TestJwt.bearer(TestJwt.ADMIN,"ADMIN"))
                        .header("Idempotency-Key", "different-key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":8000.00,\"reason\":\"Apertura única concurrente\"}"))
                        .andReturn().getResponse().getStatus();
                assertThat(status).isIn(201,409);
                return status == 201;
            };
            Future<Boolean> one = pool.submit(task);
            Future<Boolean> two = pool.submit(task);
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat((one.get(30, TimeUnit.SECONDS) ? 1 : 0) +
                    (two.get(30, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(balance(account)).isEqualByComparingTo("8000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal WHERE opening_account_id=?",
                Long.class, account)).isEqualTo(1L);
    }

    private UUID openAccount() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/accounts").header("Authorization", TestJwt.bearer(TestJwt.OPS,"OPERATIONS"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"" + CUSTOMER_A + "\",\"currency\":\"COP\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertThat(result.getResponse().getHeader("Location"))
                .contains(response.get("id").asText());
        return UUID.fromString(response.get("id").asText());
    }

    private org.springframework.test.web.servlet.ResultActions fund(UUID accountId, String key,
            String amount, String reason) throws Exception {
        return mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance", accountId)
                .header("Authorization", TestJwt.bearer(TestJwt.ADMIN,"ADMIN"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":" + amount + ",\"reason\":\"" + reason + "\"}"))
                .andExpect(status().isCreated());
    }

    private BigDecimal balance(UUID accountId) {
        return jdbc.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",
                BigDecimal.class, accountId);
    }
}
