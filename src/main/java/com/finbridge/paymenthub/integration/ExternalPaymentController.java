package com.finbridge.paymenthub.integration;

import com.finbridge.paymenthub.transfer.api.TransferResponse;
import com.finbridge.paymenthub.shared.DomainProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class ExternalPaymentController {
    private final ExternalPaymentService payments;
    private final ProviderCallbacks callbacks;
    public ExternalPaymentController(ExternalPaymentService payments,ProviderCallbacks callbacks){this.payments=payments;this.callbacks=callbacks;}
    @PostMapping("/external-transfers")
    public ResponseEntity<TransferResponse> create(@RequestBody ExternalPaymentService.Order order,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestHeader(value="X-Correlation-ID",required=false) String cid){
        if(!callbacks.configured()) throw new DomainProblem(HttpStatus.SERVICE_UNAVAILABLE,"Integración externa no configurada");
        var result=payments.reserve(order,key,cid); return ResponseEntity.accepted().location(URI.create("/api/v1/external-transfers/"+result.id())).body(result);
    }
    @GetMapping("/external-transfers/{id}") public TransferResponse get(@PathVariable UUID id){return payments.find(id);}
    @PostMapping(value="/provider/events",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Map<String,String> callback(HttpServletRequest request,@RequestHeader(value="X-Provider-Timestamp",required=false) String ts,@RequestHeader(value="X-Provider-Signature",required=false) String signature) throws IOException {
        byte[] raw=request.getInputStream().readNBytes(16385); if(raw.length>16384) throw DomainProblem.badRequest("Callback demasiado grande");
        return Map.of("outcome",callbacks.accept(ts,signature,raw));
    }
}
