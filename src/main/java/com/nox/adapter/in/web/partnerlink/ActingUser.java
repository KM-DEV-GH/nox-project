package com.nox.adapter.in.web.partnerlink;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a {@code UUID} handler parameter to the user the internal BFF acts for, from the
 * {@code X-User-ID} header (004 research.md D3).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActingUser {}
