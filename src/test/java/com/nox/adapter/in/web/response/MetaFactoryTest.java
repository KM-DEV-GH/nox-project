package com.nox.adapter.in.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.nox.support.TestTracers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class MetaFactoryTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final Instant NOW = Instant.parse("2026-10-25T09:21:41.942967Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void buildsMetaFromCurrentTraceClockAndRequestPath() {
        MetaFactory factory = new MetaFactory(TestTracers.withTraceId(TRACE_ID), clock);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setQueryString("expand=roles");

        Meta meta = factory.create(request);

        assertThat(meta.traceId()).isEqualTo(TRACE_ID).matches("^[0-9a-f]{32}$");
        assertThat(meta.timestamp()).isEqualTo(NOW);
        assertThat(meta.instance()).isEqualTo("/api/v1/me");
    }

    @Test
    void stillProducesWellFormedTraceIdWithoutCurrentSpan() {
        MetaFactory factory = new MetaFactory(TestTracers.withoutSpan(), clock);

        Meta meta = factory.create(new MockHttpServletRequest("GET", "/api/v1/me"));

        assertThat(meta.traceId()).matches("^[0-9a-f]{32}$");
    }
}
