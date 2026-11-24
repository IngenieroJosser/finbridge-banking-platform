package com.finbridge.paymenthub.integration;

import com.finbridge.paymenthub.security.CurrentActor;
import com.finbridge.paymenthub.shared.*;
import com.finbridge.paymenthub.transfer.api.TransferResponse;
import com.finbridge.paymenthub.transfer.infrastructure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Reservation, settlement and release use the same ledger and deterministic balance locks as internal transfers. */
@Service
public class ExternalPaymentService {
    static final UUID CLEARING=UUID.fromString("00000000-0000-4000-8000-000000000003");
    static final UUID CASH=UUID.fromString("00000000-0000-4000-8000-000000000002");
    static final UUID SYSTEM=UUID.fromString("00000000-0000-4000-8000-000000000094");
    static final String OP="EXTERNAL_TRANSFER";
    private final JdbcTemplate db;
    private final BankAccountLockRepository accounts;
    private final TransferIdempotencyRepository keys;
    private final AuditWriter audit;
    public ExternalPaymentService(JdbcTemplate db, BankAccountLockRepository accounts, TransferIdempotencyRepository keys, AuditWriter audit) {
        this.db=db; this.accounts=accounts; this.keys=keys; this.audit=audit;
    }
    public record Order(UUID sourceAccountId,String providerBankCode,String destinationAccountReference,BigDecimal amount,String currency,String reference) {}
    public record Payment(UUID id,UUID source,BigDecimal amount,String status,String providerId,String correlation,String bankCode) {}
    public record ProviderCallback(String eventId,String providerTransferId,UUID transferId,String status,OffsetDateTime occurredAt) {}
    @Transactional
    public TransferResponse reserve(Order o,String key,String correlation) {
        CurrentActor.require("CUSTOMER"); UUID actor=CurrentActor.id();
        TransferIdempotencyRepository.validateKey(key);
        if(o.sourceAccountId()==null || o.amount()==null || !"COP".equals(o.currency()) ||
           !safe(o.providerBankCode(),2,20) || !safe(o.destinationAccountReference(),4,64) ||
           (o.reference()!=null && (o.reference().length()>140 || o.reference().codePoints().anyMatch(Character::isISOControl))))
            throw DomainProblem.badRequest("Solicitud externa inválida");
        if(o.amount().signum()<=0 || o.amount().compareTo(new BigDecimal("50000000"))>0 || o.amount().stripTrailingZeros().scale()>2) throw DomainProblem.badRequest("Importe fuera de rango o precisión inválida");
        BigDecimal amount;
        try {amount=o.amount().setScale(2,RoundingMode.UNNECESSARY);} catch(ArithmeticException e){throw DomainProblem.badRequest("Precisión monetaria inválida");}
        if(amount.signum()<=0 || amount.compareTo(new BigDecimal("50000000"))>0) throw DomainProblem.badRequest("Importe fuera de rango");
        String reference=o.reference()==null?"":o.reference().strip();
        String hash=digest(("external:v1\n"+o.sourceAccountId()+"\n"+o.providerBankCode()+"\n"+o.destinationAccountReference()+"\n"+amount.toPlainString()+"\nCOP\n"+reference).getBytes(StandardCharsets.UTF_8));
        keys.lock(actor,OP,key);
        var prior=keys.find(actor,OP,key);
        if(prior.isPresent()) {
            if(!prior.get().requestHash().equals(hash)) throw DomainProblem.conflict("Idempotency-Key con otro contenido");
            return find(prior.get().resourceId());
        }
        var account=accounts.lock(o.sourceAccountId()); CurrentActor.requireOwner(account.customerId());
        if(!"ACTIVE".equals(account.status()) || !"CUSTOMER_LIABILITY".equals(account.accountType())) throw DomainProblem.unprocessable("Cuenta origen no operable");
        lockBalances(o.sourceAccountId(),CLEARING);
        if(balance(o.sourceAccountId()).compareTo(amount)<0) throw DomainProblem.unprocessable("Saldo disponible insuficiente");
        UUID id=UUID.randomUUID(); String cid=RequestContext.correlationId(correlation);
        db.update("""
            INSERT INTO transfer(id,source_id,amount,currency,transfer_type,status,reference,correlation_id,provider_bank_code,destination_account_reference)
            VALUES(?,?,?,'COP','EXTERNAL','RESERVED',?,?,?,?)
            """,id,o.sourceAccountId(),amount,reference,cid,o.providerBankCode(),o.destinationAccountReference());
        book(id,"EXTERNAL_RESERVE",o.sourceAccountId(),CLEARING,amount);
        db.update("INSERT INTO outbox_event(id,aggregate_id,event_type,payload) VALUES(?,?,'TransferRequested',jsonb_build_object('transferId',CAST(? AS text)))",UUID.randomUUID(),id,id.toString());
        keys.save(actor,OP,key,hash,id); audit.append("TRANSFER",id,actor,"EXTERNAL_TRANSFER_RESERVED",cid,"Reservation posted");
        return response(id);
    }
    @Transactional(readOnly=true)
    public TransferResponse find(UUID id) {
        var rows=db.query("SELECT a.customer_id FROM transfer t JOIN account a ON a.id=t.source_id WHERE t.id=? AND t.transfer_type='EXTERNAL'",(r,n)->r.getObject(1,UUID.class),id);
        if(rows.isEmpty()) throw DomainProblem.notFound("Transferencia");
        CurrentActor.readAccount(rows.getFirst()); return response(id);
    }
    TransferResponse response(UUID id) {
        return db.query("SELECT * FROM transfer WHERE id=? AND transfer_type='EXTERNAL'",(r,n)->new TransferResponse(r.getObject("id",UUID.class),"EXTERNAL",r.getString("status"),r.getBigDecimal("amount"),r.getString("currency"),r.getObject("created_at",OffsetDateTime.class),r.getString("correlation_id")),id).stream().findFirst().orElseThrow(()->DomainProblem.notFound("Transferencia"));
    }
    Payment lockPayment(UUID id) {
        return db.query("SELECT * FROM transfer WHERE id=? AND transfer_type='EXTERNAL' FOR UPDATE",(r,n)->new Payment(id,r.getObject("source_id",UUID.class),r.getBigDecimal("amount"),r.getString("status"),r.getString("provider_transfer_id"),r.getString("correlation_id"),r.getString("provider_bank_code")),id).stream().findFirst().orElseThrow(()->DomainProblem.notFound("Transferencia"));
    }
    void acknowledge(Payment p,String providerId) {
        if(p.providerId()!=null && !p.providerId().equals(providerId)) throw DomainProblem.conflict("Identificador de proveedor incompatible");
        if("RESERVED".equals(p.status())) {
            db.update("UPDATE transfer SET status='SUBMITTED',provider_transfer_id=? WHERE id=?",providerId,p.id());
            audit.append("TRANSFER",p.id(),SYSTEM,"EXTERNAL_TRANSFER_SUBMITTED",p.correlation(),"Provider accepted; not settled");
        }
    }
    void review(Payment p) {
        if(Set.of("RESERVED","SUBMITTED").contains(p.status())) {
            db.update("UPDATE transfer SET status='IN_REVIEW' WHERE id=?",p.id());
            audit.append("TRANSFER",p.id(),SYSTEM,"EXTERNAL_TRANSFER_IN_REVIEW",p.correlation(),"Delivery uncertain; reservation retained");
        }
    }
    @Transactional
    public String apply(ProviderCallback e,String hash) {
        // All consumers serialize on event ID and then on transfer row; unique constraints remain authoritative.
        keys.lock(SYSTEM,"PROVIDER_EVENT",e.eventId());
        var existing=db.query("SELECT payload_hash FROM provider_event WHERE provider='SANDBOX_BANK' AND external_event_id=?",(r,n)->r.getString(1),e.eventId());
        if(!existing.isEmpty()) {
            if(!Objects.equals(existing.getFirst(),hash)) throw DomainProblem.conflict("Evento reutilizado con otro contenido");
            return "DUPLICATE";
        }
        Payment p=lockPayment(e.transferId());
        if(p.providerId()!=null && !p.providerId().equals(e.providerTransferId())) throw DomainProblem.conflict("Identificador externo incompatible");
        // The simulator uses an independent durable receipt. A signed callback must refer to its deterministic ID.
        if(!providerId(p.id()).equals(e.providerTransferId())) throw DomainProblem.conflict("Identificador externo incompatible");
        String outcome="IGNORED";
        if(!Set.of("SETTLED","REJECTED").contains(p.status())) {
            if("RESERVED".equals(p.status())) acknowledge(p,e.providerTransferId());
            if(!Set.of("ACCEPTED","SUBMITTED","SETTLED","REJECTED").contains(e.status())) {
                review(p); outcome="IN_REVIEW";
            } else if(Set.of("SETTLED","REJECTED").contains(e.status())) {
                UUID destination="SETTLED".equals(e.status())?CASH:p.source();
                lockBalances(CLEARING,destination);
                book(p.id(),"SETTLED".equals(e.status())?"EXTERNAL_SETTLE":"EXTERNAL_RELEASE",CLEARING,destination,p.amount());
                db.update("UPDATE transfer SET status=?,provider_transfer_id=?,settlement_date=? WHERE id=?",e.status(),e.providerTransferId(),e.occurredAt().withOffsetSameInstant(java.time.ZoneOffset.UTC).toLocalDate(),p.id());
                audit.append("TRANSFER",p.id(),SYSTEM,"EXTERNAL_TRANSFER_"+e.status(),p.correlation(),"Authenticated provider result"); outcome="APPLIED";
            } else if("RESERVED".equals(p.status())) outcome="APPLIED";
        }
        db.update("INSERT INTO provider_event(id,provider,external_event_id,transfer_id,status,payload_hash,provider_transfer_id,outcome) VALUES(?,'SANDBOX_BANK',?,?,?,?,?,?)",UUID.randomUUID(),e.eventId(),p.id(),e.status(),hash,e.providerTransferId(),outcome);
        if("IGNORED".equals(outcome)) audit.append("TRANSFER",p.id(),SYSTEM,"PROVIDER_EVENT_IGNORED",p.correlation(),"Late or redundant provider event");
        return outcome;
    }
    private void lockBalances(UUID a,UUID b) {
        for(UUID id:java.util.stream.Stream.of(a,b).distinct().sorted().toList()) {
            if(db.query("SELECT account_id FROM account_balance WHERE account_id=? FOR UPDATE",(r,n)->r.getObject(1,UUID.class),id).isEmpty()) throw DomainProblem.notFound("Saldo");
        }
    }
    private BigDecimal balance(UUID id) {return db.queryForObject("SELECT available_amount FROM account_balance WHERE account_id=?",BigDecimal.class,id);}
    private void book(UUID transfer,String kind,UUID debit,UUID credit,BigDecimal amount) {
        UUID j=UUID.randomUUID(); db.update("INSERT INTO journal(id,transfer_id,journal_type) VALUES(?,?,?)",j,transfer,kind);
        for(var entry:List.of(Map.entry(debit,"DEBIT"),Map.entry(credit,"CREDIT")))
            db.update("INSERT INTO posting(id,journal_id,account_id,side,amount) VALUES(?,?,?,?,?)",UUID.randomUUID(),j,entry.getKey(),entry.getValue(),amount);
        db.update("UPDATE journal SET status='POSTED',posted_at=now() WHERE id=?",j);
    }
    static boolean safe(String s,int min,int max){return s!=null && s.length()>=min && s.length()<=max && s.matches("[A-Za-z0-9._-]+");}
    static String digest(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String providerId(UUID id){return "pay-"+id;}
}
