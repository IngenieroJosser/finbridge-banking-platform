package com.finbridge.paymenthub.reconciliation;

import com.finbridge.paymenthub.security.CurrentActor;
import com.finbridge.paymenthub.shared.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.nio.charset.*;
import java.time.LocalDate;
import java.util.*;

/** Bounded single-date reports. Only reconciliation tables and audit are written. */
@Service
public class ReconciliationService {
    private static final List<String> HEADER=List.of("provider","provider_event_id","provider_transfer_id","internal_transfer_id","amount","currency","status","settlement_date");
    private final JdbcTemplate db; private final AuditWriter audit;
    public ReconciliationService(JdbcTemplate db,AuditWriter audit){this.db=db;this.audit=audit;}
    public record Batch(UUID batchId,String status,int matched,int mismatched,LocalDate reportDate){}
    public record Item(Integer rowNumber,UUID transferId,String providerTransferId,String category,String detail){}
    public record Page(List<Item> content,int page,int size,long totalElements){}
    private record Row(int line,String event,String providerId,UUID id,BigDecimal amount,String currency,String status,LocalDate date){}
    private record Bank(UUID id,String providerId,BigDecimal amount,String currency,String status,LocalDate date){}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Batch importReport(byte[] bytes) {
        CurrentActor.require("OPERATIONS");
        if(bytes.length==0 || bytes.length>1048576)throw DomainProblem.badRequest("CSV debe contener entre 1 byte y 1 MiB");
        String content;
        try {content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw DomainProblem.badRequest("CSV debe ser UTF-8");}
        if(content.startsWith("\uFEFF"))content=content.substring(1);
        String[] lines=content.split("\\r?\\n",-1);
        if(lines.length>10002)throw DomainProblem.badRequest("Máximo 10.000 filas por reporte");
        try {if(!parseLine(lines[0]).equals(HEADER))throw new IllegalArgumentException();}catch(IllegalArgumentException e){throw DomainProblem.badRequest("Cabecera CSV inválida");}
        List<Row> valid=new ArrayList<>(); List<Item> issues=new ArrayList<>(); LocalDate date=null;
        for(int i=1;i<lines.length;i++) {
            if(lines[i].isBlank())continue;
            try {
                Row r=parseRow(i+1,lines[i]); if(date==null)date=r.date();
                if(!date.equals(r.date()))throw new IllegalArgumentException();valid.add(r);
            }catch(RuntimeException e){issues.add(new Item(i+1,null,null,"MALFORMED_ROW","Fila inválida o fecha distinta; contenido omitido"));}
        }
        if(date==null)throw DomainProblem.badRequest("Reporte sin filas válidas para determinar fecha");
        UUID batch=UUID.randomUUID(); db.update("INSERT INTO reconciliation_batch(id,report_date,status) VALUES(?,?,'PROCESSING')",batch,date);
        Set<String> events=new HashSet<>(), providerIds=new HashSet<>(); Set<UUID> seen=new HashSet<>();
        // One consistent snapshot; compare by both identifiers and retain all row discrepancies.
        for(Row r:valid) {
            boolean duplicate=!events.add(r.event()); duplicate|=!providerIds.add(r.providerId());
            if(!seen.add(r.id()))duplicate=true;
            var rows=db.query("SELECT id,provider_transfer_id,amount,currency,status,settlement_date FROM transfer WHERE id=? AND transfer_type='EXTERNAL'",(rs,n)->new Bank(rs.getObject(1,UUID.class),rs.getString(2),rs.getBigDecimal(3),rs.getString(4),rs.getString(5),rs.getObject(6,LocalDate.class)),r.id());
            if(duplicate){issues.add(new Item(r.line(),rows.isEmpty()?null:r.id(),r.providerId(),"DUPLICATE_PROVIDER_ENTRY","Identificador repetido en reporte"));continue;}
            if(rows.isEmpty()){issues.add(new Item(r.line(),null,r.providerId(),"MISSING_IN_BANK","No existe transferencia externa correspondiente"));continue;}
            Bank b=rows.getFirst(); String category="MATCHED";
            if(!Objects.equals(b.providerId(),r.providerId()))category="PROVIDER_ID_MISMATCH";
            else if(b.amount().compareTo(r.amount())!=0)category="AMOUNT_MISMATCH";
            else if(!b.currency().equals(r.currency()))category="CURRENCY_MISMATCH";
            else if(!b.status().equals(r.status()))category="STATUS_MISMATCH";
            else if(!Objects.equals(b.date(),r.date()))category="DATE_MISMATCH";
            issues.add(new Item(r.line(),b.id(),r.providerId(),category,"Comparación de identificadores, monto, moneda, estado y fecha"));
        }
        // An incomplete/malformed report cannot safely prove an operation missing at the provider.
        if(issues.stream().noneMatch(i->"MALFORMED_ROW".equals(i.category()))) {
            var candidates=db.query("SELECT id,provider_transfer_id FROM transfer WHERE transfer_type='EXTERNAL' AND settlement_date=? ORDER BY id LIMIT 10001",(r,n)->new Item(null,r.getObject(1,UUID.class),r.getString(2),"MISSING_IN_PROVIDER","Transferencia terminal sin fila en el reporte"),date);
            if(candidates.size()>10000)throw DomainProblem.badRequest("Reporte excede límite de universo bancario diario");
            candidates.stream().filter(i->!seen.contains(i.transferId())).forEach(issues::add);
        }
        for(Item i:issues)db.update("INSERT INTO reconciliation_item(id,batch_id,transfer_id,category,detail,row_number,provider_transfer_id) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),batch,i.transferId(),i.category(),i.detail(),i.rowNumber(),i.providerTransferId());
        int matched=(int)issues.stream().filter(i->"MATCHED".equals(i.category())).count();
        int mismatched=issues.size()-matched;
        db.update("UPDATE reconciliation_batch SET status='COMPLETED',matched_count=?,mismatched_count=? WHERE id=?",matched,mismatched,batch);
        audit.append("RECONCILIATION",batch,CurrentActor.id(),"RECONCILIATION_COMPLETED",RequestContext.correlationId(null),"Report classified; no ledger changes");
        return new Batch(batch,"COMPLETED",matched,mismatched,date);
    }
    @Transactional(readOnly=true)
    public Batch get(UUID id){CurrentActor.require("OPERATIONS","AUDITOR");return db.query("SELECT * FROM reconciliation_batch WHERE id=?",(r,n)->new Batch(id,r.getString("status"),r.getInt("matched_count"),r.getInt("mismatched_count"),r.getObject("report_date",LocalDate.class)),id).stream().findFirst().orElseThrow(()->DomainProblem.notFound("Lote"));}
    @Transactional(readOnly=true)
    public Page page(UUID id,int page,int size){get(id);if(page<0||page>10000||size<1||size>100)throw DomainProblem.badRequest("Paginación inválida");return new Page(items(id,size,(long)page*size),page,size,db.queryForObject("SELECT count(*) FROM reconciliation_item WHERE batch_id=?",Long.class,id));}
    @Transactional(readOnly=true)
    public String export(UUID id){get(id);StringBuilder out=new StringBuilder("row_number,transfer_id,provider_transfer_id,category,detail\r\n");for(Item i:items(id,20001,0))out.append(csv(i.rowNumber())).append(',').append(csv(i.transferId())).append(',').append(csv(i.providerTransferId())).append(',').append(csv(i.category())).append(',').append(csv(i.detail())).append("\r\n");return out.toString();}
    private List<Item> items(UUID id,int limit,long offset){return db.query("SELECT row_number,transfer_id,provider_transfer_id,category,detail FROM reconciliation_item WHERE batch_id=? ORDER BY row_number NULLS LAST,id LIMIT ? OFFSET ?",(r,n)->new Item(r.getObject(1,Integer.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5)),id,limit,offset);}
    private static String csv(Object v){if(v==null)return "";String s=v.toString();if(!s.isEmpty()&&"=+-@\t\r".indexOf(s.charAt(0))>=0)s="'"+s;return "\""+s.replace("\"","\"\"")+"\"";}
    private static Row parseRow(int line,String text){
        var c=parseLine(text);if(c.size()!=8||!"SANDBOX_BANK".equals(c.get(0))||!safe(c.get(1))||!safe(c.get(2)))throw new IllegalArgumentException();
        UUID id=UUID.fromString(c.get(3));if(!id.toString().equals(c.get(3)))throw new IllegalArgumentException();
        if(!c.get(4).matches("[0-9]{1,17}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException();BigDecimal a=new BigDecimal(c.get(4)).setScale(2);if(a.signum()<=0)throw new IllegalArgumentException();
        if(!c.get(5).matches("[A-Z]{3}")||!Set.of("SETTLED","REJECTED","ACCEPTED","SUBMITTED").contains(c.get(6)))throw new IllegalArgumentException();
        return new Row(line,c.get(1),c.get(2),id,a,c.get(5),c.get(6),LocalDate.parse(c.get(7)));
    }
    private static boolean safe(String s){return s.matches("[A-Za-z0-9._-]{1,128}");}
    /** CSV quoted commas and escaped quotes supported. Embedded newlines intentionally quarantined as physical rows. */
    static List<String> parseLine(String line){
        if(line.length()>4096||line.indexOf('\r')>=0||line.indexOf('\0')>=0)throw new IllegalArgumentException();
        List<String> fields=new ArrayList<>();StringBuilder b=new StringBuilder();boolean quoted=false,closed=false;
        for(int n=0;n<line.length();n++){char c=line.charAt(n);if(quoted){if(c=='"'){if(n+1<line.length()&&line.charAt(n+1)=='"'){b.append('"');n++;}else{quoted=false;closed=true;}}else b.append(c);}else if(c==','){fields.add(b.toString());b.setLength(0);closed=false;}else if(c=='"'&&b.isEmpty()&&!closed){quoted=true;}else{if(closed||c=='"')throw new IllegalArgumentException();b.append(c);}}
        if(quoted)throw new IllegalArgumentException();fields.add(b.toString());return fields;
    }
}
