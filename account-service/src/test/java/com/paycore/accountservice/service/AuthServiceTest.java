package com.paycore.accountservice.service;

import com.paycore.accountservice.dto.request.LoginRequest;
import com.paycore.accountservice.dto.request.LogoutRequest;
import com.paycore.accountservice.dto.request.RefreshTokenRequest;
import com.paycore.accountservice.dto.request.RegisterRequest;
import com.paycore.accountservice.dto.response.AuthResponse;
import com.paycore.accountservice.dto.response.RegisterResponse;
import com.paycore.accountservice.entity.Account;
import com.paycore.accountservice.entity.RefreshToken;
import com.paycore.accountservice.entity.User;
import com.paycore.accountservice.event.AccountCreatedEvent;
import com.paycore.accountservice.event.UserLoggedInEvent;
import com.paycore.accountservice.exception.AccountLockedException;
import com.paycore.accountservice.exception.EmailAlreadyExistsException;
import com.paycore.accountservice.exception.InvalidCredentialsException;
import com.paycore.accountservice.exception.TokenInvalidException;
import com.paycore.accountservice.kafka.EventPublisher;
import com.paycore.accountservice.repository.AccountRepository;
import com.paycore.accountservice.repository.RefreshTokenRepository;
import com.paycore.accountservice.repository.UserRepository;
import com.paycore.accountservice.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private EventPublisher eventPublisher;

    @Mock
    private AccountNumberGenerator accountNumberGenerator;

    @InjectMocks
    private AuthService authService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "refreshTokenExpirationMs", 604800000L);

        sampleUser = User.builder()
                .id(UUID.randomUUID())
                .email("fintech.user@paycore.com")
                .passwordHash("$2a$12$hashedPasswordExample")
                .fullName("Nguyen Van A")
                .phoneNumber("0901234567")
                .role(User.Role.USER)
                .kycStatus(User.KycStatus.PENDING)
                .status(User.UserStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();
    }

    // =========================================================================
    // Registration tests
    // =========================================================================

    @Test
    @DisplayName("Register happy path should create user, default account, and publish AccountCreated event")
    void register_Success() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new.user@paycore.com");
        request.setPassword("StrongPass123@");
        request.setFullName("Nguyen Van A");
        request.setPhoneNumber("0901234567");

        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userRepository.existsByPhoneNumber(request.getPhoneNumber())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(sampleUser);
        when(accountNumberGenerator.generate()).thenReturn("PC123456789012");
        when(accountRepository.save(any(Account.class))).thenAnswer(i -> {
            Account a = i.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        RegisterResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals(sampleUser.getId(), response.getUserId());
        assertEquals(sampleUser.getEmail(), response.getEmail());
        assertEquals("PENDING", response.getKycStatus());

        verify(eventPublisher, times(1)).publishAccountCreated(any(AccountCreatedEvent.class));
    }

    @Test
    @DisplayName("Register with duplicate email should throw EmailAlreadyExistsException")
    void register_DuplicateEmail_ShouldThrowException() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("duplicate@paycore.com");
        request.setPassword("StrongPass123@");
        request.setFullName("User B");

        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any());
        verify(eventPublisher, never()).publishAccountCreated(any());
    }

    // =========================================================================
    // Login tests
    // =========================================================================

    @Test
    @DisplayName("Login happy path should reset failed attempts, issue tokens, and publish UserLoggedIn event")
    void login_Success() {
        LoginRequest request = new LoginRequest();
        request.setEmail(sampleUser.getEmail());
        request.setPassword("CorrectPassword123@");

        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches(request.getPassword(), sampleUser.getPasswordHash())).thenReturn(true);
        when(jwtUtil.generateAccessToken(sampleUser)).thenReturn("sample.access.token");
        when(jwtUtil.generateRefreshToken(sampleUser)).thenReturn("sample.refresh.token");
        when(jwtUtil.getExpiration("sample.access.token")).thenReturn(new Date(System.currentTimeMillis() + 900000));

        AuthResponse response = authService.login(request);

        assertNotNull(response);
        assertEquals("sample.access.token", response.getAccessToken());
        assertEquals("sample.refresh.token", response.getRefreshToken());
        assertEquals("Bearer", response.getTokenType());

        assertEquals(0, sampleUser.getFailedLoginAttempts());
        verify(userRepository).save(sampleUser);
        verify(refreshTokenRepository).save(any(RefreshToken.class));
        verify(eventPublisher).publishUserLoggedIn(any(UserLoggedInEvent.class));
    }

    @Test
    @DisplayName("Login with wrong email should throw generic InvalidCredentialsException")
    void login_WrongEmail_ShouldThrowGenericInvalidCredentials() {
        LoginRequest request = new LoginRequest();
        request.setEmail("nonexistent@paycore.com");
        request.setPassword("AnyPassword");

        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
    }

    @Test
    @DisplayName("Login with 5 consecutive wrong passwords should lock the user account")
    void login_FiveConsecutiveFailedAttempts_ShouldLockAccount() {
        LoginRequest request = new LoginRequest();
        request.setEmail(sampleUser.getEmail());
        request.setPassword("WrongPassword");

        sampleUser.setFailedLoginAttempts(4); // 5th fail incoming
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(sampleUser));
        when(passwordEncoder.matches(request.getPassword(), sampleUser.getPasswordHash())).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));

        assertEquals(5, sampleUser.getFailedLoginAttempts());
        assertEquals(User.UserStatus.LOCKED, sampleUser.getStatus());
        verify(userRepository).save(sampleUser);
    }

    @Test
    @DisplayName("Login when account is locked out should throw AccountLockedException")
    void login_LockedOutAccount_ShouldThrowAccountLockedException() {
        LoginRequest request = new LoginRequest();
        request.setEmail(sampleUser.getEmail());
        request.setPassword("AnyPassword");

        sampleUser.setFailedLoginAttempts(5);
        sampleUser.setLastFailedLoginAt(LocalDateTime.now().minusMinutes(2)); // locked within 15 min window
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(sampleUser));

        assertThrows(AccountLockedException.class, () -> authService.login(request));
    }

    // =========================================================================
    // Refresh token rotation & security tests
    // =========================================================================

    @Test
    @DisplayName("Refresh token should rotate token pair and revoke old refresh token")
    void refresh_Success_RotatesTokens() {
        String oldRefreshToken = "raw.refresh.token.1";
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(oldRefreshToken);

        String tokenHash = AuthService.hashToken(oldRefreshToken);
        RefreshToken storedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(sampleUser.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusDays(5))
                .revoked(false)
                .build();

        when(jwtUtil.isTokenValid(oldRefreshToken)).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedToken));
        when(jwtUtil.extractUserId(oldRefreshToken)).thenReturn(sampleUser.getId());
        when(userRepository.findById(sampleUser.getId())).thenReturn(Optional.of(sampleUser));
        when(jwtUtil.generateAccessToken(sampleUser)).thenReturn("new.access.token");
        when(jwtUtil.generateRefreshToken(sampleUser)).thenReturn("new.refresh.token");
        when(jwtUtil.getExpiration("new.access.token")).thenReturn(new Date(System.currentTimeMillis() + 900000));

        AuthResponse response = authService.refresh(request);

        assertNotNull(response);
        assertEquals("new.access.token", response.getAccessToken());
        assertEquals("new.refresh.token", response.getRefreshToken());

        assertTrue(storedToken.isRevoked(), "Old refresh token must be revoked during rotation");
        verify(refreshTokenRepository).save(storedToken);
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Reusing an already revoked refresh token must revoke all tokens for that user")
    void refresh_ReusingRevokedToken_ShouldRevokeAllUserTokens() {
        String revokedToken = "already.revoked.token";
        RefreshTokenRequest request = new RefreshTokenRequest();
        request.setRefreshToken(revokedToken);

        String tokenHash = AuthService.hashToken(revokedToken);
        RefreshToken storedRevokedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(sampleUser.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusDays(5))
                .revoked(true) // already revoked!
                .build();

        when(jwtUtil.isTokenValid(revokedToken)).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedRevokedToken));

        assertThrows(TokenInvalidException.class, () -> authService.refresh(request));

        // Security response: revoke ALL tokens for this compromised account
        verify(refreshTokenRepository).revokeAllByUserId(sampleUser.getId());
    }

    @Test
    @DisplayName("Logout should revoke the given refresh token")
    void logout_ShouldRevokeToken() {
        String tokenToRevoke = "token.to.revoke";
        LogoutRequest request = new LogoutRequest();
        request.setRefreshToken(tokenToRevoke);

        String tokenHash = AuthService.hashToken(tokenToRevoke);
        RefreshToken storedToken = RefreshToken.builder()
                .id(UUID.randomUUID())
                .userId(sampleUser.getId())
                .tokenHash(tokenHash)
                .expiresAt(LocalDateTime.now().plusDays(5))
                .revoked(false)
                .build();

        when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(storedToken));

        authService.logout(request);

        assertTrue(storedToken.isRevoked());
        verify(refreshTokenRepository).save(storedToken);
    }
}
