package com.nox.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.nox.adapter.tracing.TraceContinuation;
import com.nox.domain.caller.CallerIdentity;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.trace.ReadableSpan;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes every log event as one line of JSON in the knowledge-base schema (constitution
 * Principle VII, research R9).
 *
 * <p>General context is resolved here, from the current thread, never from MDC or log arguments:
 * <ul>
 *   <li>{@code trace} from the current OpenTelemetry span, plus {@code X-Correlation-Id} (or, off
 *       the request thread, the span's correlation attribute);</li>
 *   <li>{@code messaging} from the attributes {@link TraceContinuation} puts on the current span;</li>
 *   <li>{@code request} (method, path without query string, user agent, client IP) from
 *       {@link RequestContextHolder};</li>
 *   <li>{@code user} from {@link SecurityContextHolder} when the principal is a domain
 *       {@link CallerIdentity}.</li>
 * </ul>
 * Event-specific SLF4J key-value pairs become extra top-level fields, but never replace a
 * reserved key.
 *
 * <p>Because context is read from thread-locals, this formatter must run on the thread that
 * logged the event: the console appender must stay synchronous (no {@code AsyncAppender}).
 */
public class NoxStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Set<String> RESERVED_KEYS =
            Set.of("timestamp", "level", "service", "message", "trace", "request", "messaging", "user", "exception");

    private final JsonWriter<LogEntry> writer;

    public NoxStructuredLogFormatter(Environment environment) {
        ServiceInfo service = new ServiceInfo(
                environment.getProperty("spring.application.name"),
                environment.getProperty("nox.service.version"),
                environment.getProperty("nox.service.environment"));
        this.writer = JsonWriter.<LogEntry>of(members -> {
                    members.add("timestamp", LogEntry::timestamp);
                    members.add("level", LogEntry::level);
                    addService(members, service);
                    members.add("message", LogEntry::message);
                    addTrace(members);
                    addRequest(members);
                    addMessaging(members);
                    addUser(members);
                    addException(members);
                    members.add().usingPairs(NoxStructuredLogFormatter::eventPairs);
                })
                .withNewLineAtEnd();
    }

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(LogEntry.of(event));
    }

    private static void addService(Members<LogEntry> members, ServiceInfo service) {
        members.add("service", service).usingMembers(serviceMembers -> {
            serviceMembers.add("name", ServiceInfo::name).whenNotNull();
            serviceMembers.add("version", ServiceInfo::version).whenNotNull();
            serviceMembers.add("environment", ServiceInfo::environment).whenNotNull();
        });
    }

    private static void addTrace(Members<LogEntry> members) {
        members.add("trace", LogEntry::trace).whenNotNull().usingMembers(trace -> {
            trace.add("traceId", TraceInfo::traceId).whenNotNull();
            trace.add("spanId", TraceInfo::spanId).whenNotNull();
            trace.add("parentSpanId", TraceInfo::parentSpanId).whenNotNull();
            trace.add("correlationId", TraceInfo::correlationId).whenNotNull();
        });
    }

    private static void addRequest(Members<LogEntry> members) {
        members.add("request", LogEntry::request).whenNotNull().usingMembers(request -> {
            request.add("method", RequestInfo::method);
            request.add("url", RequestInfo::url);
            request.add("userAgent", RequestInfo::userAgent).whenNotNull();
            request.add("clientIp", RequestInfo::clientIp).whenNotNull();
        });
    }

    private static void addMessaging(Members<LogEntry> members) {
        members.add("messaging", LogEntry::messaging).whenNotNull().usingMembers(messaging -> {
            messaging.add("operation", MessagingInfo::operation);
            messaging.add("exchange", MessagingInfo::exchange).whenNotNull();
            messaging.add("routingKey", MessagingInfo::routingKey).whenNotNull();
            messaging.add("queue", MessagingInfo::queue).whenNotNull();
            messaging.add("messageId", MessagingInfo::messageId).whenNotNull();
        });
    }

    private static void addUser(Members<LogEntry> members) {
        members.add("user", LogEntry::user).whenNotNull().usingMembers(user -> {
            user.add("id", UserInfo::id);
            user.add("clientId", UserInfo::clientId).whenNotNull();
        });
    }

    private static void addException(Members<LogEntry> members) {
        members.add("exception", LogEntry::exception).whenNotNull().usingMembers(exception -> {
            exception.add("type", ExceptionInfo::type);
            exception.add("message", ExceptionInfo::message).whenNotNull();
            exception.add("stacktrace", ExceptionInfo::stacktrace);
        });
    }

    private static void eventPairs(LogEntry entry, BiConsumer<String, Object> pairs) {
        entry.keyValues().forEach((key, value) -> {
            if (!RESERVED_KEYS.contains(key)) {
                pairs.accept(key, value);
            }
        });
    }

    private record LogEntry(
            Instant timestamp,
            String level,
            String message,
            @Nullable TraceInfo trace,
            @Nullable RequestInfo request,
            @Nullable MessagingInfo messaging,
            @Nullable UserInfo user,
            @Nullable ExceptionInfo exception,
            Map<String, Object> keyValues) {

        static LogEntry of(ILoggingEvent event) {
            HttpServletRequest request = currentRequest();
            return new LogEntry(
                    event.getInstant(),
                    levelOf(event.getLevel()),
                    event.getFormattedMessage(),
                    TraceInfo.current(request),
                    RequestInfo.of(request),
                    MessagingInfo.current(),
                    UserInfo.current(),
                    ExceptionInfo.of(event.getThrowableProxy()),
                    keyValuesOf(event.getKeyValuePairs()));
        }

        private static String levelOf(Level level) {
            return level.toInt() <= Level.DEBUG_INT ? Level.DEBUG.levelStr : level.levelStr;
        }

        private static Map<String, Object> keyValuesOf(@Nullable List<KeyValuePair> pairs) {
            if (pairs == null) {
                return Map.of();
            }
            Map<String, Object> keyValues = new LinkedHashMap<>();
            pairs.forEach(pair -> keyValues.putIfAbsent(pair.key, String.valueOf(pair.value)));
            return keyValues;
        }

        private static @Nullable HttpServletRequest currentRequest() {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
        }
    }

    private record ServiceInfo(
            @Nullable String name,
            @Nullable String version,
            @Nullable String environment) {}

    private record TraceInfo(
            @Nullable String traceId,
            @Nullable String spanId,
            @Nullable String parentSpanId,
            @Nullable String correlationId) {

        static @Nullable TraceInfo current(@Nullable HttpServletRequest request) {
            SpanContext span = Span.current().getSpanContext();
            String correlationId = request != null ? request.getHeader(CORRELATION_ID_HEADER) : null;
            if (correlationId == null) {
                correlationId = spanAttribute(TraceContinuation.CORRELATION_ID);
            }
            if (!span.isValid() && correlationId == null) {
                return null;
            }
            return span.isValid()
                    ? new TraceInfo(span.getTraceId(), span.getSpanId(), currentParentSpanId(), correlationId)
                    : new TraceInfo(null, null, null, correlationId);
        }

        private static @Nullable String currentParentSpanId() {
            if (Span.current() instanceof ReadableSpan readable) {
                SpanContext parent = readable.getParentSpanContext();
                return parent.isValid() ? parent.getSpanId() : null;
            }
            return null;
        }
    }

    private record RequestInfo(
            String method,
            String url,
            @Nullable String userAgent,
            @Nullable String clientIp) {

        static @Nullable RequestInfo of(@Nullable HttpServletRequest request) {
            if (request == null) {
                return null;
            }
            return new RequestInfo(
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getHeader(HttpHeaders.USER_AGENT),
                    request.getRemoteAddr());
        }
    }

    private record MessagingInfo(
            String operation,
            @Nullable String exchange,
            @Nullable String routingKey,
            @Nullable String queue,
            @Nullable String messageId) {

        static @Nullable MessagingInfo current() {
            String operation = spanAttribute(TraceContinuation.MESSAGING_OPERATION);
            if (operation == null) {
                return null;
            }
            return new MessagingInfo(
                    operation,
                    spanAttribute(TraceContinuation.MESSAGING_EXCHANGE),
                    spanAttribute(TraceContinuation.MESSAGING_ROUTING_KEY),
                    spanAttribute(TraceContinuation.MESSAGING_QUEUE),
                    spanAttribute(TraceContinuation.MESSAGING_MESSAGE_ID));
        }
    }

    private static @Nullable String spanAttribute(String key) {
        return Span.current() instanceof ReadableSpan readable
                ? readable.getAttribute(AttributeKey.stringKey(key))
                : null;
    }

    private record UserInfo(String id, @Nullable String clientId) {

        static @Nullable UserInfo current() {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof CallerIdentity caller) {
                return new UserInfo(caller.subjectId(), caller.clientId());
            }
            return null;
        }
    }

    private record ExceptionInfo(String type, @Nullable String message, List<String> stacktrace) {

        static @Nullable ExceptionInfo of(@Nullable IThrowableProxy proxy) {
            if (proxy == null) {
                return null;
            }
            List<String> lines = Arrays.stream(
                            ThrowableProxyUtil.asString(proxy).split("\\R"))
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            return new ExceptionInfo(proxy.getClassName(), proxy.getMessage(), lines);
        }
    }
}
