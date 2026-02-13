package com.paycore.accountservice.controller.internal;

import com.paycore.accountservice.entity.Account;
import com.paycore.accountservice.service.AccountService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Internal API — NOT exposed through the public API Gateway.
 * Called by Transaction Service via mTLS to validate account status before allowing transactions.
 * <p>
 * Security: protected at infrastructure level by mTLS client certificates.
 * The @Hidden annotation excludes this from the public Swagger UI.
 */
@RestController
@RequestMapping("/internal/v1/accounts")
@RequiredArgsConstructor
@Hidden  // Exclude from public Swagger UI
public class InternalAccountController {

    private final AccountService accountService;

    /**
     * GET /internal/v1/accounts/{accountId}/status
     * <p>
     * Transaction Service MUST call this before each transfer.
     * Do NOT cache the result for long — FROZEN status must take effect near-immediately.
     * Recommended max cache TTL: 5 seconds on the caller side.
     */
    @GetMapping("/{accountId}/status")
    public ResponseEntity<Map<String, String>> getAccountStatus(@PathVariable UUID accountId) {
        Account.AccountStatus status = accountService.getAccountStatus(accountId);
        return ResponseEntity.ok(Map.of(
                "accountId", accountId.toString(),
                "status", status.name()
        ));
    }
}
