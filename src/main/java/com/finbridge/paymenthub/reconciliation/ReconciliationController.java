package com.finbridge.paymenthub.reconciliation;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliations")
public class ReconciliationController {
    private final ReconciliationService service;
    public ReconciliationController(ReconciliationService service){this.service=service;}
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReconciliationService.Batch> upload(@RequestParam("file") MultipartFile file) throws IOException {
        var batch=service.importReport(file.getBytes());return ResponseEntity.accepted().location(URI.create("/api/v1/reconciliations/"+batch.batchId())).body(batch);
    }
    @GetMapping("/{id}") public ReconciliationService.Batch get(@PathVariable UUID id){return service.get(id);}
    @GetMapping("/{id}/items") public ReconciliationService.Page page(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.page(id,page,size);}
    @GetMapping(value="/{id}/export",produces="text/csv") public ResponseEntity<String> export(@PathVariable UUID id){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=finbridge-reconciliation.csv").contentType(new MediaType("text","csv",java.nio.charset.StandardCharsets.UTF_8)).body(service.export(id));}
}
