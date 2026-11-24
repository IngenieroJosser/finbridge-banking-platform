package com.finbridge.paymenthub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finbridge.paymenthub.integration.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties={"finbridge.sandbox.funding-enabled=true","finbridge.provider.worker-enabled=false","finbridge.provider.hmac-secret=fixture-only-32-byte-random-test-key"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class FinBridgeSprint4IntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){TestJwt.properties(r);r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @Autowired MockMvc mvc; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired OutboxDispatcher outbox; @Autowired ProviderCallbacks callbacks; @Autowired PlatformTransactionManager tx;
    private static final UUID CASH=UUID.fromString("00000000-0000-4000-8000-000000000002"), CLEARING=UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static String customer(){return TestJwt.bearer(TestJwt.A,"CUSTOMER");}
    private static String ops(){return TestJwt.bearer(TestJwt.OPS,"OPERATIONS");}
    private UUID funded(int amount)throws Exception{
        var r=mvc.perform(post("/api/v1/accounts").header("Authorization",ops()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("customerId",TestJwt.A,"currency","COP")))).andExpect(status().isCreated()).andReturn();
        UUID id=UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
        mvc.perform(post("/api/v1/admin/accounts/{id}/opening-balance",id).header("Authorization",TestJwt.bearer(TestJwt.ADMIN,"ADMIN")).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("amount",amount,"reason","Synthetic funding")))).andExpect(status().isCreated());return id;
    }
    private String order(UUID a,String bank,int amount)throws Exception{return json.writeValueAsString(Map.of("sourceAccountId",a,"providerBankCode",bank,"destinationAccountReference","SYNTH-1234","amount",amount,"currency","COP"));}
    private UUID reserve(UUID a,String bank,int amount)throws Exception{return reserve(a,bank,amount,UUID.randomUUID().toString());}
    private UUID reserve(UUID a,String bank,int amount,String key)throws Exception{
        var r=mvc.perform(post("/api/v1/external-transfers").header("Authorization",customer()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(order(a,bank,amount))).andExpect(status().isAccepted()).andReturn();
        return UUID.fromString(json.readTree(r.getResponse().getContentAsString()).get("id").asText());
    }
    private String state(UUID id){return db.queryForObject("SELECT status FROM transfer WHERE id=?",String.class,id);}
    private BigDecimal balance(UUID id){return db.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",BigDecimal.class,id);}
    private long count(String sql,Object...args){return db.queryForObject(sql,Long.class,args);}
    private void dispatch(UUID id){
        // Other tests can leave DLQ/terminal data, but no pending due event must steal this deterministic scenario.
        for(int i=0;i<100 && "RESERVED".equals(state(id));i++){if(!outbox.dispatchOne(false))break;}
    }
    private byte[] event(UUID id,String eventId,String status)throws Exception{return json.writeValueAsBytes(new ExternalPaymentService.ProviderCallback(eventId,ExternalPaymentService.providerId(id),id,status,OffsetDateTime.now(ZoneOffset.UTC)));}
    private void callback(byte[] raw,int expected)throws Exception{String ts=Long.toString(Instant.now().getEpochSecond());mvc.perform(post("/api/v1/provider/events").header("X-Provider-Timestamp",ts).header("X-Provider-Signature",callbacks.sign(ts,raw)).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().is(expected));}
    @Test void fb010_at13_reservation_idempotency_and_provider_acceptance_not_settlement()throws Exception{
        UUID a=funded(100000);BigDecimal before=balance(CLEARING);String key=UUID.randomUUID().toString();UUID id=reserve(a,"SANDBOX_BANK",25000,key);
        assertThat(state(id)).isEqualTo("RESERVED");assertThat(balance(a)).isEqualByComparingTo("75000");assertThat(balance(CLEARING)).isEqualByComparingTo(before.add(new BigDecimal("25000")));
        assertThat(reserve(a,"SANDBOX_BANK",25000,key)).isEqualTo(id);assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE aggregate_id=?",id)).isEqualTo(1);
        mvc.perform(post("/api/v1/external-transfers").header("Authorization",customer()).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(order(a,"SANDBOX_BANK",25001))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/external-transfers").header("Authorization",TestJwt.bearer(TestJwt.B,"CUSTOMER")).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(order(a,"SANDBOX_BANK",100))).andExpect(status().isForbidden());
        dispatch(id);assertThat(state(id)).isEqualTo("SUBMITTED");assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isEqualTo(1);
        BigDecimal cash=balance(CASH);callback(event(id,"evt-"+id,"SETTLED"),200);assertThat(state(id)).isEqualTo("SETTLED");assertThat(balance(CASH)).isEqualByComparingTo(cash.subtract(new BigDecimal("25000")));assertThat(balance(CLEARING)).isEqualByComparingTo(before);
    }
    @Test void fb010_at16_crash_after_publish_replays_durable_provider_receipt()throws Exception{
        UUID a=funded(100000),id=reserve(a,"SANDBOX_BANK",25000);
        // Previous tests leave no due pending events (DLQ or published); assert target explicitly.
        assertThatThrownBy(()->outbox.dispatchOne(true)).isInstanceOf(OutboxDispatcher.SimulatedCrash.class);
        assertThat(state(id)).isEqualTo("RESERVED");assertThat(count("SELECT count(*) FROM simulated_provider_receipt WHERE transfer_id=?",id)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=?",String.class,id)).isEqualTo("PENDING");
        dispatch(id);assertThat(state(id)).isEqualTo("SUBMITTED");assertThat(count("SELECT count(*) FROM simulated_provider_receipt WHERE transfer_id=?",id)).isEqualTo(1);
        for(int i=0;i<100 && !"SETTLED".equals(state(id));i++)if(!outbox.deliverOne())break;
        assertThat(state(id)).isEqualTo("SETTLED");assertThat(balance(a)).isEqualByComparingTo("75000");assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isEqualTo(2);
    }
    @Test void fb010_backoff_exhaustion_keeps_reservation_in_review()throws Exception{
        UUID a=funded(100000),id=reserve(a,"SANDBOX_TIMEOUT",25000);
        for(int n=1;n<=5;n++){
            assertThat(outbox.dispatchOne(false)).isTrue();
            assertThat(db.queryForObject("SELECT attempts FROM outbox_event WHERE aggregate_id=?",Integer.class,id)).isEqualTo(n);
            if(n<5){assertThat(db.queryForObject("SELECT next_attempt_at>now() FROM outbox_event WHERE aggregate_id=?",Boolean.class,id)).isTrue();db.update("UPDATE outbox_event SET next_attempt_at=now() WHERE aggregate_id=?",id);}
        }
        assertThat(state(id)).isEqualTo("IN_REVIEW");assertThat(db.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=?",String.class,id)).isEqualTo("FAILED");assertThat(balance(a)).isEqualByComparingTo("75000");assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isEqualTo(1);
    }
    @Test void fb011_at14_at15_duplicate_late_and_conflicting_callbacks()throws Exception{
        UUID a=funded(100000),id=reserve(a,"SANDBOX_BANK",25000);dispatch(id);byte[] raw=event(id,"same-"+id,"SETTLED");
        String ts=Long.toString(Instant.now().minusSeconds(301).getEpochSecond());
        mvc.perform(post("/api/v1/provider/events").header("X-Provider-Timestamp",ts).header("X-Provider-Signature",callbacks.sign(ts,raw)).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/provider/events").header("X-Provider-Timestamp",Long.toString(Instant.now().getEpochSecond())).header("X-Provider-Signature","0".repeat(64)).contentType(MediaType.APPLICATION_JSON).content(raw)).andExpect(status().isUnauthorized());
        assertThat(state(id)).isEqualTo("SUBMITTED");
        callback(raw,200);callback(raw,200);callback(event(id,"same-"+id,"REJECTED"),409);callback(event(id,"late-"+id,"SUBMITTED"),200);callback(event(id,"conflict-"+id,"REJECTED"),200);
        assertThat(state(id)).isEqualTo("SETTLED");assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isEqualTo(2);assertThat(balance(a)).isEqualByComparingTo("75000");
        mvc.perform(get("/api/v1/audit/transfers/{id}",id).header("Authorization",ops())).andExpect(status().isOk()).andExpect(jsonPath("$.postings.length()").value(4)).andExpect(jsonPath("$.journalIds.length()").value(2));
    }
    @Test void fb011_rejected_refunds_once_and_terminal_state_is_immutable()throws Exception{
        UUID a=funded(100000),id=reserve(a,"SANDBOX_REJECT",25000);dispatch(id);byte[] raw=event(id,"reject-"+id,"REJECTED");callback(raw,200);callback(raw,200);callback(event(id,"late-settle-"+id,"SETTLED"),200);
        assertThat(state(id)).isEqualTo("REJECTED");assertThat(balance(a)).isEqualByComparingTo("100000");assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=? AND journal_type='EXTERNAL_RELEASE'",id)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE transfer SET status='SUBMITTED',settlement_date=NULL WHERE id=?",id)).isInstanceOf(RuntimeException.class);
    }
    @Test void fb010_concurrent_external_debits_cannot_overspend()throws Exception{
        UUID a=funded(100000); // two concurrent external reservations exercise shared balance locking
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {Callable<Integer> task=()->{start.await(10,TimeUnit.SECONDS);return mvc.perform(post("/api/v1/external-transfers").header("Authorization",customer()).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(order(a,"SANDBOX_BANK",80000))).andReturn().getResponse().getStatus();};var one=pool.submit(task);var two=pool.submit(task);start.countDown();assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,422);}
        finally {pool.shutdownNow();}
        assertThat(balance(a)).isEqualByComparingTo("20000");UUID id=db.queryForObject("SELECT id FROM transfer WHERE source_id=?",UUID.class,a);dispatch(id);callback(event(id,"race-"+id,"SETTLED"),200);
    }
    @Test void fb011_concurrent_callbacks_are_serialized_once()throws Exception{
        UUID a=funded(100000),id=reserve(a,"SANDBOX_REJECT",25000);dispatch(id);
        byte[] raw=event(id,"race-callback-"+id,"REJECTED");ExecutorService pool=Executors.newFixedThreadPool(8);CountDownLatch start=new CountDownLatch(1);
        try{List<Future<?>> jobs=new ArrayList<>();for(int n=0;n<8;n++)jobs.add(pool.submit(()->{start.await(10,TimeUnit.SECONDS);callback(raw,200);return null;}));start.countDown();for(var f:jobs)f.get(20,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();}
        assertThat(state(id)).isEqualTo("REJECTED");assertThat(balance(a)).isEqualByComparingTo("100000");assertThat(count("SELECT count(*) FROM provider_event WHERE transfer_id=?",id)).isEqualTo(1);assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isEqualTo(2);
    }
    @Test void fb010_v7_rolls_back_reservation_without_outbox()throws Exception{
        UUID a=funded(100000),id=UUID.randomUUID();BigDecimal before=balance(CLEARING);
        assertThatThrownBy(()->new TransactionTemplate(tx).executeWithoutResult(s->{
            db.update("INSERT INTO transfer(id,source_id,amount,currency,transfer_type,status,correlation_id,provider_bank_code,destination_account_reference) VALUES(?,?,25000,'COP','EXTERNAL','RESERVED','fixture','SANDBOX_BANK','SYNTH')",id,a);
            book(id,"EXTERNAL_RESERVE",a,CLEARING,25000);
        })).isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT count(*) FROM transfer WHERE id=?",id)).isZero();assertThat(count("SELECT count(*) FROM journal WHERE transfer_id=?",id)).isZero();assertThat(balance(a)).isEqualByComparingTo("100000");assertThat(balance(CLEARING)).isEqualByComparingTo(before);
    }
    @Test void fb012_fixture_classification_and_export_do_not_mutate_ledger()throws Exception{
        UUID a=funded(1000000); // controlled synthetic bank counterparts for the supplied fixed IDs
        var txTemplate=new TransactionTemplate(tx);
        txTemplate.executeWithoutResult(s->{seed(a,101,"pay-001",150000,"SETTLED");seed(a,102,"pay-002",200000,"SETTLED");seed(a,103,"pay-003",90000,"SETTLED");seed(a,104,"pay-004",100000,"SETTLED");seed(a,105,"pay-006",40000,"SETTLED");});
        BigDecimal before=balance(a);long postings=count("SELECT count(*) FROM posting");
        byte[] csv=Files.readAllBytes(Path.of("fixtures/reconciliation_sample.csv"));
        var response=mvc.perform(multipart("/api/v1/reconciliations/import").file(new MockMultipartFile("file","report.csv","text/csv",csv)).header("Authorization",ops())).andExpect(status().isAccepted()).andReturn();
        UUID batch=UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("batchId").asText());
        var categories=db.queryForList("SELECT category FROM reconciliation_item WHERE batch_id=?",String.class,batch);
        assertThat(categories).containsExactlyInAnyOrder("MATCHED","AMOUNT_MISMATCH","STATUS_MISMATCH","MATCHED","DUPLICATE_PROVIDER_ENTRY","MISSING_IN_BANK","MISSING_IN_PROVIDER");
        assertThat(balance(a)).isEqualByComparingTo(before);assertThat(count("SELECT count(*) FROM posting")).isEqualTo(postings);
        mvc.perform(get("/api/v1/reconciliations/{id}/items",batch).param("size","2").header("Authorization",ops())).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath("$.totalElements").value(7));
        mvc.perform(get("/api/v1/reconciliations/{id}/export",batch).header("Authorization",ops())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("AMOUNT_MISMATCH")));
        mvc.perform(get("/api/v1/reconciliations/{id}",batch).header("Authorization",customer())).andExpect(status().isForbidden());
        String malformed=new String(csv,java.nio.charset.StandardCharsets.UTF_8)+"\ninvalid,row\n";
        var q=mvc.perform(multipart("/api/v1/reconciliations/import").file(new MockMultipartFile("file","bad.csv","text/csv",malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8))).header("Authorization",ops())).andExpect(status().isAccepted()).andReturn();
        UUID qb=UUID.fromString(json.readTree(q.getResponse().getContentAsString()).get("batchId").asText());assertThat(count("SELECT count(*) FROM reconciliation_item WHERE batch_id=? AND category='MALFORMED_ROW'",qb)).isEqualTo(1);
    }
    private void seed(UUID a,int suffix,String providerId,int amount,String status){
        UUID id=UUID.fromString("11111111-1111-4111-8111-111111111"+suffix); // 12-digit UUID tail
        db.update("INSERT INTO transfer(id,source_id,amount,currency,transfer_type,status,correlation_id,provider_bank_code,destination_account_reference) VALUES(?,?,?,'COP','EXTERNAL','RESERVED','fixture','SANDBOX_BANK','SYNTH')",id,a,amount);
        book(id,"EXTERNAL_RESERVE",a,CLEARING,amount);db.update("INSERT INTO outbox_event(id,aggregate_id,event_type,payload,status,published_at) VALUES(?,?,'TransferRequested','{}','PUBLISHED',now())",UUID.randomUUID(),id);
        db.update("UPDATE transfer SET status='SUBMITTED',provider_transfer_id=? WHERE id=?",providerId,id);book(id,"EXTERNAL_SETTLE",CLEARING,CASH,amount);
        db.update("UPDATE transfer SET status=?,settlement_date=DATE '2026-11-20' WHERE id=?",status,id);
    }
    private void book(UUID id,String kind,UUID debit,UUID credit,int amount){UUID j=UUID.randomUUID();db.update("INSERT INTO journal(id,transfer_id,journal_type) VALUES(?,?,?)",j,id,kind);db.update("INSERT INTO posting(id,journal_id,account_id,side,amount) VALUES(?,?,?,'DEBIT',?)",UUID.randomUUID(),j,debit,amount);db.update("INSERT INTO posting(id,journal_id,account_id,side,amount) VALUES(?,?,?,'CREDIT',?)",UUID.randomUUID(),j,credit,amount);db.update("UPDATE journal SET status='POSTED',posted_at=now() WHERE id=?",j);}
    @Test void fb013_metrics_are_role_protected_and_low_cardinality()throws Exception{
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/finbridge.outbox.pending").header("Authorization",customer())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics/finbridge.outbox.pending").header("Authorization",ops())).andExpect(status().isOk()).andExpect(jsonPath("$.measurements[0].value").exists());
        mvc.perform(get("/actuator/metrics/finbridge.transfers").header("Authorization",ops())).andExpect(status().isOk()).andExpect(jsonPath("$.availableTags[0].tag").value("status"));
    }
}
