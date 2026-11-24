package com.finbridge.paymenthub.ledger.api;

import com.finbridge.paymenthub.ledger.application.OpeningFundingService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/accounts")
@ConditionalOnProperty(prefix = "finbridge.sandbox", name = "funding-enabled", havingValue = "true")
public class OpeningFundingController {
    private final OpeningFundingService funding;
    public OpeningFundingController(OpeningFundingService funding) { this.funding = funding; }
    @PostMapping("/{accountId}/opening-balance")
    public ResponseEntity<FundingResponse> fund(@PathVariable UUID accountId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @Valid @RequestBody OpeningBalanceRequest request) {
        FundingResponse response = funding.fund(accountId, key, request, correlationId);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + accountId + "/movements")).body(response);
    }
}
