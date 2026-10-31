/**
 * Application layer: use cases ({@code service}) and the ports they expose ({@code port.in}) or
 * need ({@code port.out}).
 *
 * <p>Dependency rule: depends only on {@code java..} and {@code com.nox.domain}. The framework
 * exceptions are Spring's {@code @Transactional} and stereotype annotations ({@code @Service},
 * {@code @Component}), which are inert metadata outside a Spring context; services stay plain
 * constructor-injected classes, unit-testable with {@code new}.
 */
package com.nox.application;
