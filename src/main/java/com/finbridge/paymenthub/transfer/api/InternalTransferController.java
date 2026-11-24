package com.finbridge.paymenthub.transfer.api;

import com.finbridge.paymenthub.transfer.application.InternalTransferService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
public class InternalTransferController {
    private final InternalTransferService service;

    public InternalTransferController(InternalTransferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @Valid @RequestBody InternalTransferRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationHeader) {
        TransferResponse response = service.transfer(request, key, correlationHeader);
        org.slf4j.LoggerFactory.getLogger(getClass()).atInfo()
                .addKeyValue("event", "transfer_response").addKeyValue("transferId", response.id().toString())
                .addKeyValue("status", response.status()).log("Transfer response committed");
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + response.id()))
                .body(response);
    }

    @GetMapping("/{transferId}")
    public TransferResponse get(@PathVariable UUID transferId) {
        return service.find(transferId);
    }
}
