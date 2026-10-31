package com.nox.adapter.in.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

class SingleBearerTokenResolverTest {

    private final SingleBearerTokenResolver resolver = new SingleBearerTokenResolver();

    @Test
    void resolvesSingleBearerToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc.def.ghi");

        assertThat(resolver.resolve(request)).isEqualTo("abc.def.ghi");
    }

    @Test
    void returnsNullWithoutAuthorizationHeader() {
        assertThat(resolver.resolve(new MockHttpServletRequest())).isNull();
    }

    @Test
    void rejectsMultipleAuthorizationHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer abc.def.ghi");
        request.addHeader("Authorization", "Bearer jkl.mno.pqr");

        assertThatThrownBy(() -> resolver.resolve(request)).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void ignoresTokensInQueryParameters() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("access_token", "abc.def.ghi");

        assertThat(resolver.resolve(request)).isNull();
    }
}
