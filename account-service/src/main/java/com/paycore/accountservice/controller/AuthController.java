package com.paycore.accountservice.controller;

import com.paycore.accountservice.dto.request.*;
import com.paycore.accountservice.dto.response.*;
import com.paycore.accountservice.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login, token refresh and logout")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new user",
               description = "Creates user and default VND account. Does NOT auto-login. KYC required for transactions.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Registration successful"),
        @ApiResponse(responseCode = "400", description = "Validation failed"),
        @ApiResponse(responseCode = "409", description = "Email or phone already registered")
    })
    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Login",
               description = "Authenticate with email and password. Returns JWT access + refresh token pair.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Login successful"),
        @ApiResponse(responseCode = "401", description = "Invalid credentials"),
        @ApiResponse(responseCode = "423", description = "Account locked — too many failed attempts")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Refresh access token",
               description = "Exchange a valid refresh token for a new token pair (rotation). Detects token reuse.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Token refreshed"),
        @ApiResponse(responseCode = "401", description = "Invalid, expired or revoked refresh token")
    })
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    @Operation(summary = "Logout",
               description = "Revoke refresh token. Access token remains valid until expiry (stateless JWT).")
    @ApiResponse(responseCode = "204", description = "Logged out successfully")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }
}
