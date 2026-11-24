package com.finbridge.paymenthub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties="finbridge.sandbox.funding-enabled=true")
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class FinBridgeSprint3IntegrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        TestJwt.properties(r);
        r.add("spring.datasource.url",DB::getJdbcUrl); r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private static String token(String actor,String role) { return TestJwt.bearer(actor,role); }
    private UUID open(String customer) throws Exception {
        var result=mvc.perform(post("/api/v1/accounts").header("Authorization",token(TestJwt.OPS,"OPERATIONS"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\""+customer+"\",\"currency\":\"COP\"}"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
    }
    private void fund(UUID account) throws Exception {
        mvc.perform(fundRequest(account).header("Authorization",token(TestJwt.ADMIN,"ADMIN"))).andExpect(status().isCreated());
    }
    private MockHttpServletRequestBuilder fundRequest(UUID account) {
        return post("/api/v1/admin/accounts/{id}/opening-balance",account).header("Idempotency-Key",UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100000,\"reason\":\"Synthetic funding\"}");
    }
    private MockHttpServletRequestBuilder transfer(UUID a,UUID b,String key,String extra) {
        return post("/api/v1/transfers").header("Idempotency-Key",key).header("X-Correlation-ID","sprint3-test")
                .contentType(MediaType.APPLICATION_JSON).content("{\"sourceAccountId\":\""+a+"\",\"destinationAccountId\":\""+b+"\",\"amount\":25000,\"currency\":\"COP\""+extra+"}");
    }
    @Test void at12_missing_token_and_invalid_signatures_claims() throws Exception {
        UUID account=open(TestJwt.A);
        mvc.perform(get("/api/v1/accounts/{id}/balance",account)).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("WWW-Authenticate","Bearer")).andExpect(jsonPath("$.correlationId").isString());
        String[] invalid={"Bearer garbage",
                TestJwt.issue(TestJwt.A,"CUSTOMER",TestJwt.ISSUER,TestJwt.AUDIENCE,Instant.now().plusSeconds(300),true),
                TestJwt.issue(TestJwt.A,"CUSTOMER","https://other.test",TestJwt.AUDIENCE,Instant.now().plusSeconds(300),false),
                TestJwt.issue(TestJwt.A,"CUSTOMER",TestJwt.ISSUER,"other-api",Instant.now().plusSeconds(300),false),
                TestJwt.issue(TestJwt.A,"CUSTOMER",TestJwt.ISSUER,TestJwt.AUDIENCE,Instant.now().minusSeconds(120),false),
                TestJwt.issue(TestJwt.A,"CUSTOMER",TestJwt.ISSUER,TestJwt.AUDIENCE,null,false),
                TestJwt.issue("not-a-uuid","CUSTOMER",TestJwt.ISSUER,TestJwt.AUDIENCE,Instant.now().plusSeconds(300),false)};
        for(String bearer:invalid) mvc.perform(get("/api/v1/accounts/{id}/balance",account).header("Authorization",bearer))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/v1/accounts/{id}/balance",account).header("Authorization",token(TestJwt.A,"UNKNOWN")))
                .andExpect(status().isForbidden());
    }
    @Test void at11_idor_both_accounts_and_read_endpoints_no_mutations() throws Exception {
        UUID a=open(TestJwt.A), b=open(TestJwt.A), c=open(TestJwt.B); fund(a);
        var outsider=token(TestJwt.B,"CUSTOMER"); var owner=token(TestJwt.A,"CUSTOMER");
        String key=UUID.randomUUID().toString();
        mvc.perform(transfer(a,b,key,"").header("Authorization",outsider)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.correlationId").value("sprint3-test"));
        mvc.perform(transfer(a,c,key,"").header("Authorization",owner)).andExpect(status().isForbidden());
        mvc.perform(transfer(a,b,key,",\"ownerId\":\""+TestJwt.A+"\"").header("Authorization",outsider)).andExpect(status().isBadRequest());
        for(String path:new String[]{"/api/v1/accounts/{id}","/api/v1/accounts/{id}/balance"})
            mvc.perform(get(path,a).header("Authorization",outsider)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE source_id=?",Long.class,a)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key=?",Long.class,key)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",java.math.BigDecimal.class,a)).isEqualByComparingTo("100000");
        var result=mvc.perform(transfer(a,b,key,"").header("Authorization",owner)).andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(get("/api/v1/transfers/{id}",id).header("Authorization",outsider)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/transfers/{id}",id).header("Authorization",owner)).andExpect(status().isOk());
    }
    @Test void rbac_and_idempotency_per_authenticated_actor() throws Exception {
        UUID a=open(TestJwt.A),b=open(TestJwt.A),c=open(TestJwt.B),d=open(TestJwt.B); fund(a);fund(c);
        String key=UUID.randomUUID().toString();
        for(String role:new String[]{"AUDITOR","OPERATIONS","ADMIN"})
            mvc.perform(transfer(a,b,key,"").header("Authorization",token(TestJwt.A,role))).andExpect(status().isForbidden());
        for(String role:new String[]{"CUSTOMER","AUDITOR","OPERATIONS"})
            mvc.perform(fundRequest(b).header("Authorization",token(TestJwt.A,role))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/accounts").header("Authorization",token(TestJwt.A,"AUDITOR"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"customerId\":\""+TestJwt.A+"\",\"currency\":\"COP\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(transfer(a,b,key,"").header("Authorization",token(TestJwt.A,"CUSTOMER"))).andExpect(status().isCreated());
        mvc.perform(transfer(c,d,key,"").header("Authorization",token(TestJwt.B,"CUSTOMER"))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT actor_id) FROM idempotency_record WHERE operation='INTERNAL_TRANSFER' AND idempotency_key=?",Long.class,key)).isEqualTo(2L);
    }
    @Test void fb008_audit_actor_correlation_ledger_and_append_only() throws Exception {
        UUID a=open(TestJwt.A),b=open(TestJwt.A);fund(a);
        String key=UUID.randomUUID().toString();
        var result=mvc.perform(transfer(a,b,key,"").header("Authorization",token(TestJwt.A,"CUSTOMER")))
                .andExpect(status().isCreated()).andExpect(header().string("X-Correlation-ID","sprint3-test")).andReturn();
        UUID id=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(get("/api/v1/audit/transfers/{id}",id).header("Authorization",token(TestJwt.A,"CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/transfers/{id}",id).header("Authorization",token(TestJwt.AUDITOR,"AUDITOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correlationId").value("sprint3-test"))
                .andExpect(jsonPath("$.events[0].actorId").value(TestJwt.A)).andExpect(jsonPath("$.postings.length()").value(2));
        assertThatThrownBy(()->jdbc.update("UPDATE audit_event SET event_type='FAKE' WHERE aggregate_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM audit_event WHERE aggregate_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE audit_event")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE account SET customer_id=? WHERE id=?",UUID.fromString(TestJwt.B),a)).isInstanceOf(RuntimeException.class);
        mvc.perform(get("/api/v1/accounts/not-a-uuid/balance").header("Authorization",token(TestJwt.A,"CUSTOMER")))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.correlationId").isString());
        mvc.perform(get("/api/v1/accounts/{id}/balance",a).header("Authorization",token(TestJwt.A,"CUSTOMER")).header("X-Correlation-ID","bad value"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.correlationId").isString());
    }
}
