package com.nox.integration.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContractAssertionsTest {

    private static final String META =
            "\"meta\":{\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\",\"timestamp\":\"2026-10-25T09:00:00Z\","
                    + "\"instance\":\"/api/v1/me\"}";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void acceptsBodyMatchingTheContract() {
        assertThatCode(() -> ContractAssertions.assertMatchesSchema(
                        caller("\"USER\"", "\"alice\"", ""), "CurrentCallerResponse"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownField() {
        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(
                        caller("\"USER\"", "\"alice\"", ",\"token\":\"x\""), "CurrentCallerResponse"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsValueOutsideEnum() {
        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(
                        caller("\"ROBOT\"", "\"alice\"", ""), "CurrentCallerResponse"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsMissingRequiredField() {
        JsonNode body = jsonMapper.readTree("{\"data\":{\"subjectId\":\"s\"}," + META + "}");

        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(body, "CurrentCallerResponse"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void rejectsMalformedTraceId() {
        JsonNode problem = jsonMapper.readTree(
                "{\"type\":\"urn:problem:forbidden\",\"title\":\"forbidden\","
                        + "\"detail\":\"d\",\"traceId\":\"NOT-HEX\",\"timestamp\":\"2026-10-25T09:00:00Z\",\"instance\":\"/\"}");

        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(problem, "Problem"))
                .isInstanceOf(AssertionError.class);
    }

    private JsonNode caller(String callerType, String username, String extra) {
        return jsonMapper.readTree("{\"data\":{\"subjectId\":\"s\",\"callerType\":" + callerType
                + ",\"username\":" + username + ",\"clientId\":null,\"email\":null,\"roles\":[]" + extra + "},"
                + META + "}");
    }
}
