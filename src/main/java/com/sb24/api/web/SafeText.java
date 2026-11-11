package com.sb24.api.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Allow-list for free text (XSS / injection defence at the edge): letters, digits, spaces and a small set of
 * punctuation. Markup characters (< > " ` { } \ $ |) and control characters are rejected, not "sanitised",
 * so what is stored is exactly what was validated. Output is additionally protected by JSON-only responses,
 * X-Content-Type-Options: nosniff and a CSP of default-src 'none'.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SafeText.Validator.class)
public @interface SafeText {
    String message() default "texto contém caracteres não permitidos";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<SafeText, String> {
        private static final Pattern ALLOWED = Pattern.compile("^[\\p{L}\\p{N} .,;:'’()\\-_/@#+&%]*$");

        @Override
        public boolean isValid(String value, ConstraintValidatorContext ctx) {
            return value == null || (Normalizer.isNormalized(value, Normalizer.Form.NFC) && ALLOWED.matcher(value).matches());
        }
    }
}
