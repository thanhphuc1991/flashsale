package com.flashsale.auth.repository;

import com.flashsale.auth.domain.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpRepository extends JpaRepository<OtpVerification, Long> {
    Optional<OtpVerification> findFirstByIdentifierAndPurposeAndConsumedFalseOrderByCreatedAtDesc(
            String identifier, String purpose);
}
