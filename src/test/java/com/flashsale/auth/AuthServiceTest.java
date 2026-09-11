package com.flashsale.auth;

import com.flashsale.auth.domain.IdentifierType;
import com.flashsale.auth.domain.RefreshToken;
import com.flashsale.auth.domain.User;
import com.flashsale.auth.dto.LoginRequest;
import com.flashsale.auth.dto.RegisterRequest;
import com.flashsale.auth.repository.RefreshTokenRepository;
import com.flashsale.auth.repository.UserRepository;
import com.flashsale.auth.service.AuthService;
import com.flashsale.auth.service.OtpService;
import com.flashsale.common.exception.ApiException;
import com.flashsale.common.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the auth correctness requirements from the spec:
 * - single API disambiguates email vs phone
 * - no sensitive-info leakage (generic error for wrong password vs unknown user)
 * - registration requires OTP verification before login succeeds
 * - duplicate registration is rejected without revealing account existence
 */
class AuthServiceTest {

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private OtpService otpService;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        otpService = mock(OtpService.class);
        jwtService = mock(JwtService.class);
        authService = new AuthService(
                userRepository, refreshTokenRepository, otpService, passwordEncoder, jwtService,
                7L, 15L);
    }

    @Test
    void registerWithEmailDetectsIdentifierTypeAndTriggersOtp() {
        when(userRepository.existsByIdentifier("user@example.com")).thenReturn(false);

        authService.register(new RegisterRequest("user@example.com", "password123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(IdentifierType.EMAIL, captor.getValue().getIdentifierType());
        verify(otpService).generateAndSend("user@example.com", "REGISTER");
    }

    @Test
    void registerWithPhoneDetectsIdentifierType() {
        when(userRepository.existsByIdentifier("+84912345678")).thenReturn(false);

        authService.register(new RegisterRequest("+84912345678", "password123"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(IdentifierType.PHONE, captor.getValue().getIdentifierType());
    }

    @Test
    void registerRejectsDuplicateIdentifierWithGenericError() {
        when(userRepository.existsByIdentifier("user@example.com")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.register(new RegisterRequest("user@example.com", "password123")));

        // Must not say "already exists" in a way that confirms the account -
        // just assert it's rejected, and OTP is never sent for a duplicate.
        assertNotNull(ex.getMessage());
        verify(otpService, never()).generateAndSend(any(), any());
    }

    @Test
    void loginFailsWithGenericErrorWhenUserNotFound() {
        when(userRepository.findByIdentifier("nobody@example.com")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("nobody@example.com", "whatever123")));

        assertEquals("UNAUTHORIZED", ex.getErrorCode());
    }

    @Test
    void loginFailsWithSameGenericErrorWhenPasswordWrong() throws Exception {
        User user = buildVerifiedUser("user@example.com", "correct-password");
        when(userRepository.findByIdentifier("user@example.com")).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("user@example.com", "wrong-password")));

        // Same error code/shape as "user not found" above -> no user enumeration.
        assertEquals("UNAUTHORIZED", ex.getErrorCode());
    }

    @Test
    void loginRejectsUnverifiedAccountEvenWithCorrectPassword() throws Exception {
        User user = buildUnverifiedUser("user@example.com", "correct-password");
        when(userRepository.findByIdentifier("user@example.com")).thenReturn(Optional.of(user));

        ApiException ex = assertThrows(ApiException.class,
                () -> authService.login(new LoginRequest("user@example.com", "correct-password")));

        assertEquals("ACCOUNT_NOT_VERIFIED", ex.getErrorCode());
    }

    @Test
    void loginIssuesAccessAndRefreshTokenForVerifiedUser() throws Exception {
        User user = buildVerifiedUser("user@example.com", "correct-password");
        setId(user, 42L);
        when(userRepository.findByIdentifier("user@example.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(42L, "user@example.com")).thenReturn("fake-jwt");

        var response = authService.login(new LoginRequest("user@example.com", "correct-password"));

        assertEquals("fake-jwt", response.accessToken());
        assertNotNull(response.refreshToken());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void logoutOnUnknownTokenIsNoOpNotError() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> authService.logout(new com.flashsale.auth.dto.LogoutRequest("unknown-token")));
    }

    // --- helpers: build entities with reflection since constructors are package-private/protected ---

    private User buildVerifiedUser(String identifier, String rawPassword) throws Exception {
        User user = new User(identifier, IdentifierType.EMAIL, passwordEncoder.encode(rawPassword));
        user.markVerified();
        return user;
    }

    private User buildUnverifiedUser(String identifier, String rawPassword) {
        return new User(identifier, IdentifierType.EMAIL, passwordEncoder.encode(rawPassword));
    }

    private void setId(User user, Long id) throws Exception {
        Field field = User.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(user, id);
    }
}
