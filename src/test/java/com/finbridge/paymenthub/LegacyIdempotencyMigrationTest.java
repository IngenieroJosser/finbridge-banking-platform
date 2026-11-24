package com.finbridge.paymenthub;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Forward-only migration with legacy data, not just a fresh schema. */
@Testcontainers
class LegacyIdempotencyMigrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void v6_keeps_historical_audit_and_prevents_reprocessing_legacy_key() {
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        Flyway.configure().dataSource(ds).target("5").load().migrate();
        var jdbc=new JdbcTemplate(ds);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        UUID actor=UUID.fromString(TestJwt.OPS), owner=UUID.fromString(TestJwt.A);
        UUID bank=UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID a=UUID.randomUUID(), b=UUID.randomUUID(), opening=UUID.randomUUID(), id=UUID.randomUUID(), journal=UUID.randomUUID(), event=UUID.randomUUID();
        String key="legacy-key-00000001", hash="a".repeat(64);
        tx.executeWithoutResult(s->{
            for(UUID account:new UUID[]{a,b}) {
                jdbc.update("INSERT INTO account(id,customer_id,account_type) VALUES(?,?,'CUSTOMER_LIABILITY')",account,owner);
                jdbc.update("INSERT INTO account_balance(account_id) VALUES(?)",account);
            }
            jdbc.update("INSERT INTO journal(id,opening_account_id,journal_type,currency,status) VALUES(?,?,'OPENING_FUNDING','COP','DRAFT')",opening,a);
            posting(jdbc,opening,bank,"DEBIT",100); posting(jdbc,opening,a,"CREDIT",100);
            jdbc.update("UPDATE journal SET status='POSTED',posted_at=now() WHERE id=?",opening);
            jdbc.update("INSERT INTO transfer(id,source_id,destination_id,amount,currency,transfer_type,status,correlation_id) VALUES(?,?,?,25,'COP','INTERNAL','COMPLETED','legacy-test')",id,a,b);
            jdbc.update("INSERT INTO journal(id,transfer_id,journal_type,currency,status) VALUES(?,?,'INTERNAL_TRANSFER','COP','DRAFT')",journal,id);
            posting(jdbc,journal,a,"DEBIT",25);posting(jdbc,journal,b,"CREDIT",25);
            jdbc.update("UPDATE journal SET status='POSTED',posted_at=now() WHERE id=?",journal);
            jdbc.update("INSERT INTO idempotency_record(actor_id,operation,idempotency_key,request_hash,resource_id) VALUES(?,'INTERNAL_TRANSFER',?,?,?)",actor,key,hash,id);
            jdbc.update("INSERT INTO audit_event(id,aggregate_type,aggregate_id,actor_id,event_type,correlation_id) VALUES(?,'TRANSFER',?,?,'INTERNAL_TRANSFER_COMPLETED','legacy-test')",event,id,actor);
        });
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(jdbc.queryForObject("SELECT actor_id FROM idempotency_record WHERE resource_id=?",UUID.class,id)).isEqualTo(owner);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM idempotency_record WHERE resource_id=?",String.class,id)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_event WHERE id=?",UUID.class,event)).isEqualTo(actor);
        assertThat(jdbc.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",java.math.BigDecimal.class,a)).isEqualByComparingTo("75");
        assertThat(jdbc.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",java.math.BigDecimal.class,b)).isEqualByComparingTo("25");
    }
    private static void posting(JdbcTemplate jdbc,UUID j,UUID a,String side,int amount) {
        jdbc.update("INSERT INTO posting(id,journal_id,account_id,side,amount,currency) VALUES(?,?,?,?,?,'COP')",UUID.randomUUID(),j,a,side,amount);
    }
}
