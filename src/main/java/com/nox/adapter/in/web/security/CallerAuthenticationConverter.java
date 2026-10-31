package com.nox.adapter.in.web.security;

import com.nox.domain.caller.CallerIdentity;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated token into a {@link CallerAuthentication}. Keycloak roles become
 * {@code ROLE_<name>} authorities; scopes grant nothing (research R5).
 */
public class CallerAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtCallerIdentityMapper identityMapper;

    public CallerAuthenticationConverter(JwtCallerIdentityMapper identityMapper) {
        this.identityMapper = identityMapper;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CallerIdentity caller = identityMapper.map(jwt);
        List<GrantedAuthority> authorities = caller.roles().stream()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .toList();
        return new CallerAuthentication(caller, authorities);
    }
}
