package com.flashsale.auth.service;

import com.flashsale.auth.domain.OtpVerification;
import com.flashsale.auth.repository.OtpRepository;
import com.flashsale.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * OTP is "sent" via log line (+ persisted row), as the assignment allows
 * mocking delivery. In production this would call an SMS/email provider from
 * an outbox-driven worker instead of inline.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int OTP_TTL_MINUTES = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final OtpRepository otpRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public OtpService(OtpRepository otpRepository) {
        this.otpRepository = otpRepository;
    }

    @Transactional
    public void generateAndSend(String identifier, String purpose) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String codeHash = passwordEncoder.encode(code);
        OtpVerification otp = new OtpVerification(
                identifier, codeHash, purpose, Instant.now().plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        otpRepository.save(otp);

        // Mock send: log + DB row is the "delivery". Never log the raw code in
        // a real system, but the assignment explicitly asks for mock send via log/DB.
        log.info("[MOCK-OTP] Sending OTP to {} for purpose={} code={}", identifier, purpose, code);
    }

    @Transactional
    public void verify(String identifier, String purpose, String submittedCode) {
        OtpVerification otp = otpRepository
                .findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(identifier, purpose)
                .orElseThrow(() -> ApiException.badRequest("OTP_NOT_FOUND", "No active OTP for this identifier"));

        if (otp.isConsumed() || otp.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("OTP_EXPIRED", "OTP has expired, please request a new one");
        }
        if (otp.getAttempts() >= MAX_ATTEMPTS) {
            throw ApiException.badRequest("OTP_LOCKED", "Too many attempts, please request a new OTP");
        }

        otp.incrementAttempts();
        if (!passwordEncoder.matches(submittedCode, otp.getCodeHash())) {
            throw ApiException.badRequest("OTP_INVALID", "Invalid OTP code");
        }
        otp.consume();
    }
}
