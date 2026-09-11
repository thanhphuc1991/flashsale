package com.flashsale.auth.service;

import com.flashsale.auth.domain.IdentifierType;
import com.flashsale.auth.domain.RefreshToken;
import com.flashsale.auth.domain.User;
import com.flashsale.auth.dto.*;
import com.flashsale.auth.repository.RefreshTokenRepository;
import com.flashsale.auth.repository.UserRepository;
import com.flashsale.common.exception.ApiException;
import com.flashsale.common.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class AuthService {

    private static final int REFRESH_TOKEN_BYTES = 48;
    private final SecureRandom secureRandom = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final long refreshTokenTtlDays;
    private final long accessTokenTtlMinutes;

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        OtpService otpService,
                        PasswordEncoder passwordEncoder,
                        JwtService jwtService,
                        @Value("${app.jwt.refresh-token-ttl-days}") long refreshTokenTtlDays,
                        @Value("${app.jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpService = otpService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenTtlDays = refreshTokenTtlDays;
        this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        IdentifierType type = IdentifierDetector.detect(request.identifier());
        String normalized = IdentifierDetector.normalize(request.identifier(), type);

        if (userRepository.existsByIdentifier(normalized)) {
            // Generic message: don't reveal whether the account exists to avoid enumeration.
            throw ApiException.conflict("REGISTRATION_FAILED", "Unable to register with the provided identifier");
        }

        User user = new User(normalized, type, passwordEncoder.encode(request.password()));
        userRepository.save(user);

        otpService.generateAndSend(normalized, "REGISTER");
        return new RegisterResponse(normalized, "Registered. Please verify the OTP sent to your email/phone.");
    }

    @Transactional
    public void verifyRegistration(OtpVerifyRequest request) {
        otpService.verify(request.identifier(), "REGISTER", request.otpCode());
        User user = userRepository.findByIdentifier(request.identifier())
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
        user.markVerified();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        IdentifierType type = IdentifierDetector.detect(request.identifier());
        String normalized = IdentifierDetector.normalize(request.identifier(), type);

        User user = userRepository.findByIdentifier(normalized)
                .orElseThrow(() -> ApiException.unauthorized("Invalid credentials"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Same generic error for "wrong password" and "user not found" above.
            throw ApiException.unauthorized("Invalid credentials");
        }
        if (!user.isVerified()) {
            throw ApiException.badRequest("ACCOUNT_NOT_VERIFIED", "Please verify your account via OTP first");
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        String tokenHash = sha256(request.refreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("Refresh token expired or revoked");
        }

        // Rotate: revoke the old refresh token and issue a new pair.
        stored.revoke();
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        return issueTokens(user);
    }

    @Transactional
    public void logout(LogoutRequest request) {
        String tokenHash = sha256(request.refreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(RefreshToken::revoke);
        // Idempotent by design: logging out an already-revoked/unknown token is a no-op, not an error.
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getIdentifier());

        byte[] randomBytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawRefreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String tokenHash = sha256(rawRefreshToken);

        RefreshToken refreshToken = new RefreshToken(
                user.getId(), tokenHash, Instant.now().plus(refreshTokenTtlDays, ChronoUnit.DAYS));
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, rawRefreshToken, accessTokenTtlMinutes * 60);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
