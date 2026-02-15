package com.paycore.accountservice.service;

import com.paycore.accountservice.dto.request.*;
import com.paycore.accountservice.dto.response.*;
import com.paycore.accountservice.entity.*;
import com.paycore.accountservice.event.*;
import com.paycore.accountservice.exception.*;
import com.paycore.accountservice.kafka.EventPublisher;
import com.paycore.accountservice.repository.*;
import com.paycore.accountservice.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_WINDOW_MINUTES = 15;

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EventPublisher eventPublisher;
    private final AccountNumberGenerator accountNumberGenerator;

    @Value("${jwt.refresh-token-expiration-ms}")
    private long refreshTokenExpirationMs;

    // =========================================================================
    // Register
    // =========================================================================

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        // 1. Uniqueness checks
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }
        if (request.getPhoneNumber() != null && userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new PhoneAlreadyExistsException(request.getPhoneNumber());
        }

        // 2. Create user
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phoneNumber(request.getPhoneNumber())
                .role(User.Role.USER)
                .kycStatus(User.KycStatus.PENDING)
                .status(User.UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);
        log.info("User registered: userId={}, email={}", user.getId(), user.getEmail());

        // 3. Auto-create default VND account
        String accountNumber = accountNumberGenerator.generate();
        Account account = Account.builder()
                .userId(user.getId())
                .accountNumber(accountNumber)
                .currency("VND")
                .status(Account.AccountStatus.ACTIVE)
                .build();
        account = accountRepository.save(account);
        log.info("Default account created: accountId={}, accountNumber={}", account.getId(), accountNumber);

        // 4. Publish AccountCreated event → Ledger Service initializes balance=0
        eventPublisher.publishAccountCreated(AccountCreatedEvent.builder()
                .userId(user.getId())
                .accountId(account.getId())
                .accountNumber(account.getAccountNumber())
                .currency(account.getCurrency())
                .build());

        return RegisterResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .kycStatus(user.getKycStatus().name())
                .message("Registration successful. Please complete KYC verification to enable transactions.")
                .build();
    }

    // =========================================================================
    // Login
    // =========================================================================

    @Transactional
    public AuthResponse login(LoginRequest request) {
        // Generic error — never reveal whether email exists (prevent email enumeration)
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        // Check if admin-locked (different from auto-lockout)
        if (!user.isActive() && user.getStatus() == User.UserStatus.LOCKED) {
            if (user.getFailedLoginAttempts() == 0) {
                // Admin-manually locked
                throw new AccountLockedException("Account has been suspended. Please contact support.");
            }
        }

        // Check auto-lockout
        if (isLockedOut(user)) {
            throw new AccountLockedException();
        }

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            handleFailedLogin(user);
            throw new InvalidCredentialsException();
        }

        // Reset on success
        user.resetFailedLoginAttempts();
        userRepository.save(user);

        // Generate tokens
        String accessToken  = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        // Persist hashed refresh token
        saveRefreshToken(user, refreshToken, null);

        // Publish audit event (no sensitive data)
        eventPublisher.publishUserLoggedIn(UserLoggedInEvent.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .loginAt(LocalDateTime.now())
                .build());

        long expiresIn = jwtUtil.getExpiration(accessToken).getTime() - System.currentTimeMillis();
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(expiresIn / 1000)
                .tokenType("Bearer")
                .build();
    }

    // =========================================================================
    // Refresh Token (rotation)
    // =========================================================================

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawToken = request.getRefreshToken();

        // 1. Validate JWT signature + expiry
        if (!jwtUtil.isTokenValid(rawToken)) {
            throw new TokenInvalidException();
        }

        // 2. Lookup by hash
        String hash = hashToken(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(TokenInvalidException::new);

        // 3. Detect reuse of revoked token → security alert
        if (stored.isRevoked()) {
            log.warn("SECURITY ALERT: Revoked refresh token reused for userId={}. Revoking all tokens.",
                    stored.getUserId());
            refreshTokenRepository.revokeAllByUserId(stored.getUserId());
            throw new TokenInvalidException("Suspicious activity detected. Please login again.");
        }

        if (!stored.isValid()) {
            throw new TokenInvalidException();
        }

        // 4. Rotate: revoke old, issue new pair
        stored.revoke();
        refreshTokenRepository.save(stored);

        UUID userId = jwtUtil.extractUserId(rawToken);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));

        String newAccessToken  = jwtUtil.generateAccessToken(user);
        String newRefreshToken = jwtUtil.generateRefreshToken(user);
        saveRefreshToken(user, newRefreshToken, null);

        long expiresIn = jwtUtil.getExpiration(newAccessToken).getTime() - System.currentTimeMillis();
        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(expiresIn / 1000)
                .tokenType("Bearer")
                .build();
    }

    // =========================================================================
    // Logout
    // =========================================================================

    @Transactional
    public void logout(LogoutRequest request) {
        String hash = hashToken(request.getRefreshToken());
        refreshTokenRepository.findByTokenHash(hash)
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                    log.info("Refresh token revoked for userId={}", token.getUserId());
                });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isLockedOut(User user) {
        if (user.getFailedLoginAttempts() < MAX_FAILED_ATTEMPTS) return false;
        if (user.getLastFailedLoginAt() == null) return false;
        return user.getLastFailedLoginAt()
                .isAfter(LocalDateTime.now().minusMinutes(LOCKOUT_WINDOW_MINUTES));
    }

    private void handleFailedLogin(User user) {
        user.incrementFailedLoginAttempts();
        if (user.getFailedLoginAttempts() >= MAX_FAILED_ATTEMPTS) {
            user.lock();
            log.warn("Account locked due to too many failed attempts: userId={}", user.getId());
        }
        userRepository.save(user);
    }

    private void saveRefreshToken(User user, String rawToken, String deviceInfo) {
        LocalDateTime expiry = LocalDateTime.now()
                .plusNanos(refreshTokenExpirationMs * 1_000_000);
        RefreshToken token = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(hashToken(rawToken))
                .expiresAt(expiry)
                .deviceInfo(deviceInfo)
                .build();
        refreshTokenRepository.save(token);
    }

    /**
     * SHA-256 hash of raw token string.
     * Stored in DB — raw token never persisted.
     */
    public static String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
