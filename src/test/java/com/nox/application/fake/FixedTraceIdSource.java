package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.TraceIdSource;

public class FixedTraceIdSource implements TraceIdSource {

    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Override
    public String currentTraceId() {
        return TRACE_ID;
    }
}
