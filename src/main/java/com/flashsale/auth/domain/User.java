package com.flashsale.auth.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String identifier;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false)
    private IdentifierType identifierType;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "is_verified", nullable = false)
    private boolean verified = false;

    @Column(nullable = false)
    private BigDecimal balance;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {}

    public User(String identifier, IdentifierType identifierType, String passwordHash) {
        this.identifier = identifier;
        this.identifierType = identifierType;
        this.passwordHash = passwordHash;
    }

    public Long getId() { return id; }
    public String getIdentifier() { return identifier; }
    public IdentifierType getIdentifierType() { return identifierType; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isVerified() { return verified; }
    public void markVerified() { this.verified = true; this.updatedAt = Instant.now(); }
    public BigDecimal getBalance() { return balance; }
    public String getStatus() { return status; }
}
