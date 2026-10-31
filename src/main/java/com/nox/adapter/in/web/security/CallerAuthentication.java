package com.nox.adapter.in.web.security;

import com.nox.domain.caller.CallerIdentity;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

/**
 * An authenticated request whose principal is the domain {@link CallerIdentity}. The token is not
 * kept: credentials are always {@code null}.
 */
public class CallerAuthentication extends AbstractAuthenticationToken {

    private final CallerIdentity caller;

    public CallerAuthentication(CallerIdentity caller, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.caller = caller;
        setAuthenticated(true);
    }

    @Override
    public CallerIdentity getPrincipal() {
        return caller;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return caller.subjectId();
    }
}
