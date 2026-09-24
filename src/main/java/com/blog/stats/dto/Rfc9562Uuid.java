package com.blog.stats.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.UUID;

/**
 * UUID au format RFC 9562 (variante 10xx, version 1 à 8), comme {@code crypto.randomUUID()}.
 * Java accepte n'importe quels 128 bits, mais le type UUID de MariaDB en rejette certains :
 * sans ce contrôle, l'INSERT échouerait en 500 au lieu d'un 400.
 */
@Documented
@Constraint(validatedBy = Rfc9562Uuid.Validator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface Rfc9562Uuid {

    String message() default "must be an RFC 9562 UUID (version 1-8)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<Rfc9562Uuid, UUID> {

        @Override
        public boolean isValid(UUID value, ConstraintValidatorContext context) {
            return value == null || (value.variant() == 2 && value.version() >= 1 && value.version() <= 8);
        }
    }
}
