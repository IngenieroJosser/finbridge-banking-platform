package com.finbridge.paymenthub.account.api;

import com.finbridge.paymenthub.account.application.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest body,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {
        AccountResponse created = accounts.open(body, correlationId);
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + created.id()))
                .body(created);
    }

    @GetMapping("/{accountId}")
    public AccountResponse get(@PathVariable UUID accountId) {
        return accounts.find(accountId);
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable UUID accountId) {
        return accounts.balance(accountId);
    }
}
