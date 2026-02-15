package com.paycore.accountservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paycore.accountservice.dto.request.LoginRequest;
import com.paycore.accountservice.dto.request.LogoutRequest;
import com.paycore.accountservice.dto.request.RefreshTokenRequest;
import com.paycore.accountservice.dto.request.RegisterRequest;
import com.paycore.accountservice.dto.response.AuthResponse;
import com.paycore.accountservice.kafka.EventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventPublisher eventPublisher; // Mock Kafka in integration test

    @Test
    @DisplayName("Complete Auth Flow: Register -> Login -> Get Me -> Get Accounts -> Refresh -> Logout -> Reuse Detection")
    void completeAuthLifecycleFlow() throws Exception {
        // 1. Register
        RegisterRequest registerReq = new RegisterRequest();
        registerReq.setEmail("integration.tester@paycore.com");
        registerReq.setPassword("P@ssw0rd2025!");
        registerReq.setFullName("Integration Tester");
        registerReq.setPhoneNumber("0912345678");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("integration.tester@paycore.com"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"));

        // 2. Login
        LoginRequest loginReq = new LoginRequest();
        loginReq.setEmail("integration.tester@paycore.com");
        loginReq.setPassword("P@ssw0rd2025!");

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        AuthResponse authResponse = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthResponse.class);

        String accessToken = authResponse.getAccessToken();
        String refreshToken = authResponse.getRefreshToken();
        assertNotNull(accessToken);
        assertNotNull(refreshToken);

        // 3. Get /api/v1/users/me with Bearer token
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("integration.tester@paycore.com"))
                .andExpect(jsonPath("$.fullName").value("Integration Tester"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()); // Security: never expose password hash

        // 4. Get /api/v1/accounts/me with Bearer token
        mockMvc.perform(get("/api/v1/accounts/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountNumber").isNotEmpty())
                .andExpect(jsonPath("$[0].currency").value("VND"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].balance").doesNotExist()); // Domain: balance lives in ledger-service

        // 5. Refresh token rotation
        RefreshTokenRequest refreshReq = new RefreshTokenRequest();
        refreshReq.setRefreshToken(refreshToken);

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        AuthResponse rotatedResponse = objectMapper.readValue(
                refreshResult.getResponse().getContentAsString(), AuthResponse.class);

        String newRefreshToken = rotatedResponse.getRefreshToken();

        // 6. Security Check: Reusing old revoked refresh token should fail with 401
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshReq)))
                .andExpect(status().isUnauthorized());

        // 7. Logout with new refresh token
        LogoutRequest logoutReq = new LogoutRequest();
        logoutReq.setRefreshToken(newRefreshToken);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(logoutReq)))
                .andExpect(status().isNoContent());
    }
}
