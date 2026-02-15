package com.paycore.accountservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.accountservice.config.JwtConfig;
import com.paycore.accountservice.dto.request.LoginRequest;
import com.paycore.accountservice.dto.request.RegisterRequest;
import com.paycore.accountservice.dto.response.AuthResponse;
import com.paycore.accountservice.dto.response.RegisterResponse;
import com.paycore.accountservice.exception.AccountLockedException;
import com.paycore.accountservice.exception.EmailAlreadyExistsException;
import com.paycore.accountservice.exception.InvalidCredentialsException;
import com.paycore.accountservice.security.JwtAuthFilter;
import com.paycore.accountservice.security.JwtUtil;
import com.paycore.accountservice.security.UserDetailsServiceImpl;
import com.paycore.accountservice.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class)
@Import(com.paycore.accountservice.exception.GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false) // Disable security filters for isolated controller unit test
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtConfig jwtConfig;

    @Test
    @DisplayName("POST /api/v1/auth/register should return 201 on valid request")
    void register_ValidRequest_Returns201() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("user@paycore.com");
        request.setPassword("StrongPass123@");
        request.setFullName("Nguyen Van A");
        request.setPhoneNumber("0901234567");

        RegisterResponse response = RegisterResponse.builder()
                .userId(UUID.randomUUID())
                .email(request.getEmail())
                .kycStatus("PENDING")
                .message("Registration successful")
                .build();

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("user@paycore.com"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register should return 400 on weak password")
    void register_WeakPassword_Returns400() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("user@paycore.com");
        request.setPassword("weak"); // Less than 8 chars, no special char
        request.setFullName("Nguyen Van A");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/register should return 409 on duplicate email")
    void register_DuplicateEmail_Returns409() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("existing@paycore.com");
        request.setPassword("StrongPass123@");
        request.setFullName("Nguyen Van A");

        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException(request.getEmail()));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login should return 200 with tokens on valid credentials")
    void login_ValidCredentials_Returns200() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@paycore.com");
        request.setPassword("StrongPass123@");

        AuthResponse response = AuthResponse.builder()
                .accessToken("mock.jwt.token")
                .refreshToken("mock.refresh.token")
                .expiresIn(900)
                .tokenType("Bearer")
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("mock.jwt.token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login should return 401 on invalid credentials")
    void login_InvalidCredentials_Returns401() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@paycore.com");
        request.setPassword("WrongPassword");

        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Email or password is incorrect"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/login should return 423 when account is locked")
    void login_AccountLocked_Returns423() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("user@paycore.com");
        request.setPassword("WrongPassword");

        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new AccountLockedException());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("ACCOUNT_LOCKED"));
    }
}
