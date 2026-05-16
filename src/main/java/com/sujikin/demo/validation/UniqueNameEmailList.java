package com.sujikin.demo.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = UniqueNameEmailListValidator.class)
public @interface UniqueNameEmailList {
    String message() default "Duplicate name and email combinations found in the list";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
