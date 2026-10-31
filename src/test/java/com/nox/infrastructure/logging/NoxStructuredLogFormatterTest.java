package com.nox.infrastructure.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.nox.adapter.tracing.TraceContinuation;
import com.nox.domain.caller.CallerIdentity;
import com.nox.domain.caller.CallerType;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class NoxStructuredLogFormatterTest {

    private static final SdkTracerProvider TRACER_PROVIDER =
            SdkTracerProvider.builder().build();

    private final Tracer tracer = TRACER_PROVIDER.get("test");
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final Logger logger = new LoggerContext().getLogger("com.nox.Test");
    private final NoxStructuredLogFormatter formatter = new NoxStructuredLogFormatter(new MockEnvironment()
            .withProperty("spring.application.name", "nox")
            .withProperty("nox.service.version", "1.2.3")
            .withProperty("nox.service.environment", "test"));

    @AfterEach
    void clearRequestAndSecurityContext() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void closeTracerProvider() {
        TRACER_PROVIDER.close();
    }

    @Test
    void writesSingleLineJsonWithMandatoryFields() {
        String line = formatter.format(event(Level.INFO, "hello world", null));

        assertThat(line.strip()).doesNotContain("\n");
        JsonNode json = jsonMapper.readTree(line);
        assertThat(json.get("timestamp").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z");
        assertThat(json.get("level").asString()).isEqualTo("INFO");
        assertThat(json.get("service").get("name").asString()).isEqualTo("nox");
        assertThat(json.get("service").get("version").asString()).isEqualTo("1.2.3");
        assertThat(json.get("service").get("environment").asString()).isEqualTo("test");
        assertThat(json.get("message").asString()).isEqualTo("hello world");
    }

    @Test
    void omitsContextObjectsWhenThereIsNoContext() {
        JsonNode json = jsonMapper.readTree(formatter.format(event(Level.INFO, "no context", null)));

        assertThat(json.has("trace")).isFalse();
        assertThat(json.has("request")).isFalse();
        assertThat(json.has("user")).isFalse();
        assertThat(json.has("exception")).isFalse();
    }

    @Test
    void mapsLevels() {
        assertThat(levelOf(Level.TRACE)).isEqualTo("DEBUG");
        assertThat(levelOf(Level.DEBUG)).isEqualTo("DEBUG");
        assertThat(levelOf(Level.INFO)).isEqualTo("INFO");
        assertThat(levelOf(Level.WARN)).isEqualTo("WARN");
        assertThat(levelOf(Level.ERROR)).isEqualTo("ERROR");
    }

    @Test
    void resolvesTraceFromCurrentSpanWithoutParentForRootSpan() {
        Span root = tracer.spanBuilder("root").startSpan();
        try (Scope ignored = root.makeCurrent()) {
            JsonNode trace = jsonMapper
                    .readTree(formatter.format(event(Level.INFO, "traced", null)))
                    .get("trace");

            assertThat(trace.get("traceId").asString())
                    .isEqualTo(root.getSpanContext().getTraceId());
            assertThat(trace.get("spanId").asString())
                    .isEqualTo(root.getSpanContext().getSpanId());
            assertThat(trace.has("parentSpanId")).isFalse();
        } finally {
            root.end();
        }
    }

    @Test
    void resolvesParentSpanIdWhenSpanHasParent() {
        Span parent = tracer.spanBuilder("parent").startSpan();
        Span child = tracer.spanBuilder("child")
                .setParent(Context.current().with(parent))
                .startSpan();
        try (Scope ignored = child.makeCurrent()) {
            JsonNode trace = jsonMapper
                    .readTree(formatter.format(event(Level.INFO, "traced", null)))
                    .get("trace");

            assertThat(trace.get("spanId").asString())
                    .isEqualTo(child.getSpanContext().getSpanId());
            assertThat(trace.get("parentSpanId").asString())
                    .isEqualTo(parent.getSpanContext().getSpanId());
        } finally {
            child.end();
            parent.end();
        }
    }

    @Test
    void resolvesCorrelationIdFromRequestHeaderOnlyWhenPresent() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "corr-123");
        bind(request);

        JsonNode json = jsonMapper.readTree(formatter.format(event(Level.INFO, "correlated", null)));
        assertThat(json.get("trace").get("correlationId").asString()).isEqualTo("corr-123");

        bind(request());
        JsonNode withoutHeader = jsonMapper.readTree(formatter.format(event(Level.INFO, "plain", null)));
        assertThat(withoutHeader.has("trace")).isFalse();
    }

    @Test
    void resolvesMessagingAndCorrelationFromTheCurrentSpan() {
        Span span = tracer.spanBuilder("partner-link publish")
                .setAttribute(TraceContinuation.MESSAGING_OPERATION, "publish")
                .setAttribute(TraceContinuation.MESSAGING_EXCHANGE, "partnerlink.events")
                .setAttribute(TraceContinuation.MESSAGING_ROUTING_KEY, "partner-link.approved.v1")
                .setAttribute(TraceContinuation.MESSAGING_MESSAGE_ID, "message-1")
                .setAttribute(TraceContinuation.CORRELATION_ID, "corr-456")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            JsonNode json = jsonMapper.readTree(formatter.format(event(Level.INFO, "message published", null)));

            JsonNode messaging = json.get("messaging");
            assertThat(messaging.get("operation").asString()).isEqualTo("publish");
            assertThat(messaging.get("exchange").asString()).isEqualTo("partnerlink.events");
            assertThat(messaging.get("routingKey").asString()).isEqualTo("partner-link.approved.v1");
            assertThat(messaging.get("messageId").asString()).isEqualTo("message-1");
            assertThat(messaging.has("queue")).isFalse();
            assertThat(json.get("trace").get("correlationId").asString()).isEqualTo("corr-456");
        } finally {
            span.end();
        }
    }

    @Test
    void omitsMessagingForAnOrdinarySpan() {
        Span span = tracer.spanBuilder("http").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            JsonNode json = jsonMapper.readTree(formatter.format(event(Level.INFO, "plain", null)));

            assertThat(json.has("messaging")).isFalse();
            assertThat(json.get("trace").has("correlationId")).isFalse();
        } finally {
            span.end();
        }
    }

    @Test
    void theCorrelationHeaderWinsOverTheSpan() {
        MockHttpServletRequest request = request();
        request.addHeader("X-Correlation-Id", "from-header");
        bind(request);
        Span span = tracer.spanBuilder("span")
                .setAttribute(TraceContinuation.CORRELATION_ID, "from-span")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            JsonNode json = jsonMapper.readTree(formatter.format(event(Level.INFO, "both", null)));

            assertThat(json.get("trace").get("correlationId").asString()).isEqualTo("from-header");
        } finally {
            span.end();
        }
    }

    @Test
    void resolvesRequestWithPathOnly() {
        MockHttpServletRequest request = request();
        request.setQueryString("access_token=secret-value");
        request.addHeader("Authorization", "Bearer secret-value");
        bind(request);

        String line = formatter.format(event(Level.INFO, "request", null));
        JsonNode json = jsonMapper.readTree(line);

        JsonNode requestNode = json.get("request");
        assertThat(requestNode.get("method").asString()).isEqualTo("GET");
        assertThat(requestNode.get("url").asString()).isEqualTo("/api/v1/me");
        assertThat(requestNode.get("userAgent").asString()).isEqualTo("curl/8.0");
        assertThat(requestNode.get("clientIp").asString()).isEqualTo("203.0.113.7");
        assertThat(line).doesNotContain("secret-value");
    }

    @Test
    void writesExceptionWithStackTraceArray() {
        IllegalStateException failure = new IllegalStateException("boom", new IllegalArgumentException("cause"));

        JsonNode exception = jsonMapper
                .readTree(formatter.format(event(Level.ERROR, "failed", failure)))
                .get("exception");

        assertThat(exception.get("type").asString()).isEqualTo("java.lang.IllegalStateException");
        assertThat(exception.get("message").asString()).isEqualTo("boom");
        assertThat(exception.get("stacktrace").isArray()).isTrue();
        assertThat(exception.get("stacktrace").size()).isGreaterThan(1);
        assertThat(exception.get("stacktrace").get(1).asString()).contains("NoxStructuredLogFormatterTest");
    }

    @Test
    void writesEventKeyValuesAsTopLevelFieldsWithoutOverridingReservedKeys() {
        LoggingEvent event = event(Level.WARN, "authentication failed", null);
        event.addKeyValuePair(new KeyValuePair("reason", "token_expired"));
        event.addKeyValuePair(new KeyValuePair("level", "CRITICAL"));
        event.addKeyValuePair(new KeyValuePair("message", "spoofed"));
        event.addKeyValuePair(new KeyValuePair("user", "spoofed"));

        JsonNode json = jsonMapper.readTree(formatter.format(event));

        assertThat(json.get("reason").asString()).isEqualTo("token_expired");
        assertThat(json.get("level").asString()).isEqualTo("WARN");
        assertThat(json.get("message").asString()).isEqualTo("authentication failed");
        assertThat(json.has("user")).isFalse();
    }

    @Test
    void ignoresMdc() {
        LoggingEvent event = event(Level.INFO, "mdc", null);
        event.setMDCPropertyMap(Map.of("mdcKey", "mdc-value", "traceId", "from-mdc"));

        String line = formatter.format(event);

        assertThat(line).doesNotContain("mdc-value").doesNotContain("from-mdc").doesNotContain("mdcKey");
    }

    @Test
    void resolvesUserFromAuthenticatedCaller() {
        authenticate(new CallerIdentity(
                "user-1", CallerType.USER, "alice", null, "alice@example.test", Set.of("nox-tester")));

        String line = formatter.format(event(Level.INFO, "as user", null));
        JsonNode user = jsonMapper.readTree(line).get("user");

        assertThat(user.get("id").asString()).isEqualTo("user-1");
        assertThat(user.has("clientId")).isFalse();
        assertThat(line).doesNotContain("alice@example.test");
    }

    @Test
    void resolvesClientIdForServiceCaller() {
        authenticate(new CallerIdentity("service-1", CallerType.SERVICE, null, "nox-test-client", null, Set.of()));

        JsonNode user = jsonMapper
                .readTree(formatter.format(event(Level.INFO, "as service", null)))
                .get("user");

        assertThat(user.get("id").asString()).isEqualTo("service-1");
        assertThat(user.get("clientId").asString()).isEqualTo("nox-test-client");
    }

    @Test
    void omitsUserWhenNotAuthenticatedAsCaller() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        assertThat(jsonMapper
                        .readTree(formatter.format(event(Level.INFO, "anonymous", null)))
                        .has("user"))
                .isFalse();

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("someone", null));
        assertThat(jsonMapper
                        .readTree(formatter.format(event(Level.INFO, "other principal", null)))
                        .has("user"))
                .isFalse();
    }

    @Test
    void neverReadsUserFromMdcOrArguments() {
        LoggingEvent event = new LoggingEvent(
                Logger.class.getName(), logger, Level.INFO, "user {}", null, new Object[] {"argument-user"});
        event.setMDCPropertyMap(Map.of("user.id", "mdc-user"));

        assertThat(jsonMapper.readTree(formatter.format(event)).has("user")).isFalse();
    }

    private static void authenticate(CallerIdentity caller) {
        TestingAuthenticationToken authentication = new TestingAuthenticationToken(caller, null, "ROLE_nox-tester");
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String levelOf(Level level) {
        return jsonMapper
                .readTree(formatter.format(event(level, "level", null)))
                .get("level")
                .asString();
    }

    private LoggingEvent event(Level level, String message, Throwable throwable) {
        return new LoggingEvent(Logger.class.getName(), logger, level, message, throwable, null);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("User-Agent", "curl/8.0");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    private static void bind(MockHttpServletRequest request) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
