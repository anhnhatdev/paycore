package com.paycore.accountservice.controller;

import com.paycore.accountservice.dto.response.AccountResponse;
import com.paycore.accountservice.service.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Tag(name = "Accounts", description = "Account management — status only (balance is in ledger-service)")
public class AccountController {

    private final AccountService accountService;

    @Operation(summary = "Get current user's accounts",
               description = "Returns list of accounts for the authenticated user. Balance NOT included.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me")
    public ResponseEntity<List<AccountResponse>> getMyAccounts(
            @AuthenticationPrincipal UserDetails userDetails) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        return ResponseEntity.ok(accountService.getAccountsByUserId(userId));
    }

    @Operation(summary = "Freeze an account (ADMIN only)",
               description = "Immediately freezes the account. Publishes AccountFrozen event to block transactions.",
               security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponse(responseCode = "200", description = "Account frozen")
    @ApiResponse(responseCode = "403", description = "ADMIN role required")
    @ApiResponse(responseCode = "404", description = "Account not found")
    @PostMapping("/{id}/freeze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AccountResponse> freezeAccount(
            @Parameter(description = "Account UUID") @PathVariable UUID id) {
        return ResponseEntity.ok(accountService.freezeAccount(id));
    }
}
