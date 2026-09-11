package com.flashsale.auth;

import com.flashsale.auth.domain.OtpVerification;
import com.flashsale.auth.repository.OtpRepository;
import com.flashsale.auth.service.OtpService;
import com.flashsale.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OtpServiceTest {

    private OtpRepository otpRepository;
    private OtpService otpService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setUp() {
        otpRepository = mock(OtpRepository.class);
        otpService = new OtpService(otpRepository);
        when(otpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void verifyFailsWhenNoActiveOtpExists() {
        when(otpRepository.findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(
                "user@example.com", "REGISTER")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> otpService.verify("user@example.com", "REGISTER", "123456"));
        assertEquals("OTP_NOT_FOUND", ex.getErrorCode());
    }

    @Test
    void verifyFailsWhenOtpExpired() throws Exception {
        OtpVerification expired = buildOtp("111111", Instant.now().minus(1, ChronoUnit.MINUTES));
        when(otpRepository.findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(expired));

        ApiException ex = assertThrows(ApiException.class,
                () -> otpService.verify("user@example.com", "REGISTER", "111111"));
        assertEquals("OTP_EXPIRED", ex.getErrorCode());
    }

    @Test
    void verifyFailsWithWrongCode() throws Exception {
        OtpVerification otp = buildOtp("222222", Instant.now().plus(5, ChronoUnit.MINUTES));
        when(otpRepository.findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(otp));

        ApiException ex = assertThrows(ApiException.class,
                () -> otpService.verify("user@example.com", "REGISTER", "999999"));
        assertEquals("OTP_INVALID", ex.getErrorCode());
    }

    @Test
    void verifySucceedsAndConsumesOtpWithCorrectCode() throws Exception {
        OtpVerification otp = buildOtp("333333", Instant.now().plus(5, ChronoUnit.MINUTES));
        when(otpRepository.findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(otp));

        assertDoesNotThrow(() -> otpService.verify("user@example.com", "REGISTER", "333333"));
        assertTrue(otp.isConsumed());
    }

    @Test
    void verifyLocksOutAfterMaxAttempts() throws Exception {
        OtpVerification otp = buildOtp("444444", Instant.now().plus(5, ChronoUnit.MINUTES));
        setAttempts(otp, 5);
        when(otpRepository.findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(any(), any()))
                .thenReturn(Optional.of(otp));

        ApiException ex = assertThrows(ApiException.class,
                () -> otpService.verify("user@example.com", "REGISTER", "444444"));
        assertEquals("OTP_LOCKED", ex.getErrorCode());
    }

    private OtpVerification buildOtp(String rawCode, Instant expiresAt) {
        return new OtpVerification("user@example.com", passwordEncoder.encode(rawCode), "REGISTER", expiresAt);
    }

    private void setAttempts(OtpVerification otp, int attempts) throws Exception {
        Field field = OtpVerification.class.getDeclaredField("attempts");
        field.setAccessible(true);
        field.set(otp, attempts);
    }
}
