package com.finbridge.paymenthub.audit;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit/transfers")
public class TransferAuditController {
    private final TransferAuditService audit;
    public TransferAuditController(TransferAuditService audit) { this.audit = audit; }
    @GetMapping("/{id}")
    public TransferAuditService.Evidence get(@PathVariable UUID id) { return audit.find(id); }
}
