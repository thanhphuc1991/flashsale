package com.flashsale.auth.service;

import com.flashsale.auth.domain.IdentifierType;
import com.flashsale.common.exception.ApiException;

import java.util.regex.Pattern;

/**
 * Distinguishes email vs phone from a single "identifier" input field, so
 * register/login/logout can each be exposed as ONE API per the spec.
 */
public final class IdentifierDetector {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]{8,15}$");

    private IdentifierDetector() {}

    public static IdentifierType detect(String identifier) {
        if (EMAIL.matcher(identifier).matches()) return IdentifierType.EMAIL;
        if (PHONE.matcher(identifier).matches()) return IdentifierType.PHONE;
        throw ApiException.badRequest("INVALID_IDENTIFIER", "Identifier must be a valid email or phone number");
    }

    public static String normalize(String identifier, IdentifierType type) {
        return type == IdentifierType.EMAIL ? identifier.trim().toLowerCase() : identifier.trim();
    }
}
