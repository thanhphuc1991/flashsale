package com.flashsale.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "otp_verification", indexes = {
        // Backs findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc,
        // run on every OTP verify attempt.
        @Index(name = "idx_otp_identifier", columnList = "identifier, purpose, consumed")
})
public class OtpVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String identifier;

    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    @Column(nullable = false)
    private String purpose = "REGISTER";

    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private boolean consumed = false;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected OtpVerification() {}

    public OtpVerification(String identifier, String codeHash, String purpose, Instant expiresAt) {
        this.identifier = identifier;
        this.codeHash = codeHash;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getIdentifier() { return identifier; }
    public String getCodeHash() { return codeHash; }
    public boolean isConsumed() { return consumed; }
    public void consume() { this.consumed = true; }
    public int getAttempts() { return attempts; }
    public void incrementAttempts() { this.attempts++; }
    public Instant getExpiresAt() { return expiresAt; }
}
