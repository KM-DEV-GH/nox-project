package com.nox.adapter.out.identityprovider;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.RemoteKeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.io.Closeable;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realm's signing keys, fetched from the JWKS endpoint and verified against locally
 * (research R3). Never calls Keycloak per request:
 * <ul>
 *   <li>keys are cached and refreshed ahead of expiry in the background;</li>
 *   <li>an unknown key ID triggers an immediate, rate-limited refresh (key rotation);</li>
 *   <li>when Keycloak is unreachable, the last loaded keys keep being used;</li>
 *   <li>fetches time out, so a slow Keycloak fails a request quickly instead of hanging it.</li>
 * </ul>
 * When a token needs a key that cannot be obtained because fetching fails, this source throws
 * {@link RemoteKeySourceException}, which the decoder reports as a service-side failure (503),
 * not as an invalid token (FR-011).
 */
public class SigningKeySource implements JWKSource<SecurityContext>, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(SigningKeySource.class);

    private static final int RESPONSE_SIZE_LIMIT_BYTES = 50 * 1024;
    private static final Duration CACHE_TIME_TO_LIVE = Duration.ofMinutes(5);
    private static final Duration INITIAL_PRELOAD_BACKOFF = Duration.ofSeconds(1);
    private static final Duration MAX_PRELOAD_BACKOFF = Duration.ofSeconds(30);
    private static final JWKSelector ALL_KEYS = new JWKSelector(new JWKMatcher.Builder().build());

    private final JWKSource<SecurityContext> keys;
    private final SigningKeyStateTracker tracker;
    private volatile Thread preload;

    public SigningKeySource(
            URI jwksUri, Duration connectTimeout, Duration readTimeout, SigningKeyStateTracker tracker) {
        this.tracker = tracker;
        DefaultResourceRetriever http = new DefaultResourceRetriever(
                Math.toIntExact(connectTimeout.toMillis()),
                Math.toIntExact(readTimeout.toMillis()),
                RESPONSE_SIZE_LIMIT_BYTES);
        this.keys = JWKSourceBuilder.create(toUrl(jwksUri), new StateReportingResourceRetriever(http, tracker))
                .cache(
                        CACHE_TIME_TO_LIVE.toMillis(),
                        connectTimeout.plus(readTimeout).toMillis())
                .refreshAheadCache(JWKSourceBuilder.DEFAULT_REFRESH_AHEAD_TIME, true)
                .rateLimited(true)
                .outageTolerantForever()
                .build();
    }

    @Override
    public List<JWK> get(JWKSelector selector, SecurityContext context) throws KeySourceException {
        List<JWK> matches;
        try {
            matches = keys.get(selector, context);
        } catch (RateLimitReachedException e) {
            matches = List.of();
        }
        if (matches.isEmpty() && tracker.state() != SigningKeyState.LOADED) {
            throw new RemoteKeySourceException("Signing keys unavailable", null);
        }
        return matches;
    }

    /** Loads the keys in the background, retrying with exponential backoff until the first success. */
    public void start() {
        preload = Thread.ofVirtual().name("signing-key-preload").start(this::preloadUntilLoaded);
    }

    @Override
    public void close() throws IOException {
        Thread running = preload;
        if (running != null) {
            running.interrupt();
        }
        if (keys instanceof Closeable closeable) {
            closeable.close();
        }
    }

    private void preloadUntilLoaded() {
        Duration backoff = INITIAL_PRELOAD_BACKOFF;
        while (!tracker.isReady() && !Thread.currentThread().isInterrupted()) {
            try {
                keys.get(ALL_KEYS, null);
            } catch (KeySourceException e) {
                LOG.atWarn().addKeyValue("reason", "jwks_unavailable").log("signing keys not loaded yet");
            }
            if (tracker.isReady()) {
                return;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = nextBackoff(backoff);
        }
    }

    private static Duration nextBackoff(Duration current) {
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(MAX_PRELOAD_BACKOFF) > 0 ? MAX_PRELOAD_BACKOFF : doubled;
    }

    private static URL toUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid JWKS URI", e);
        }
    }
}
