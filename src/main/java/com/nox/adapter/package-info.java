/**
 * Adapter layer: inbound ({@code in}, e.g. REST controllers) and outbound ({@code out}, e.g.
 * identity provider clients) implementations of application ports.
 *
 * <p>Dependency rule: may depend on {@code application}, {@code domain}, and frameworks. Inbound and
 * outbound adapters never depend on each other, and nothing here depends on
 * {@code com.nox.infrastructure}.
 */
package com.nox.adapter;
