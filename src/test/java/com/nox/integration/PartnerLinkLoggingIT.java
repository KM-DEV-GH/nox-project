package com.nox.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.nox.domain.partnerlink.PartnerLinkRequest;
import com.nox.integration.support.InfrastructureContainers;
import com.nox.integration.support.LinkScenarios.Link;
import com.nox.integration.support.OwlWireMockSupport;
import com.nox.integration.support.PartnerLinkApi;
import com.nox.integration.support.PartnerLinkIntegrationSupport;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Principles VII and XI: the lookup listener and message publications log the {@code messaging}
 * fields with the originating request's {@code traceId}, and no email, Owl URL, api-key or token is
 * ever logged.
 */
@ExtendWith(OutputCaptureExtension.class)
class PartnerLinkLoggingIT extends PartnerLinkIntegrationSupport {

    private static final Duration ASYNC = Duration.ofSeconds(15);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void asynchronousWorkLogsMessagingContextWithTheRequestTraceId(CapturedOutput output) {
        String alice = TOKENS.user("alice");
        String bff = TOKENS.bff();
        String email = OwlWireMockSupport.uniqueEmail();
        String userId = UUID.randomUUID().toString();
        owl.userFound(email, userId);

        EntityExchangeResult<String> accepted =
                api.submit(partner, UUID.randomUUID().toString(), "external-1", email);
        String submitTraceId = traceIdOf(PartnerLinkApi.json(accepted));
        PartnerLinkRequest request = scenarios.awaitLookup(PartnerLinkApi.requestIdOf(accepted));

        JsonNode lookup = awaitEntry(
                output,
                entry -> "resolving link request".equals(text(entry, "message"))
                        && submitTraceId.equals(traceIdOf(entry)));
        assertThat(lookup.get("messaging").get("operation").asString()).isEqualTo("process");
        assertThat(lookup.get("messaging").get("messageId").asString()).isNotBlank();
        assertThat(lookup.get("trace").get("correlationId").asString())
                .isEqualTo(request.correlationId().toString());

        JsonNode approvalRequested = awaitPublished(output, submitTraceId, "partner-link.approval-requested.v1");
        assertThat(approvalRequested.get("messaging").get("exchange").asString())
                .isEqualTo("partnerlink.events");

        Link link = new Link(request.id(), request.linkId(), userId, email, "external-1", request.correlationId());
        String approveTraceId =
                traceIdOf(PartnerLinkApi.json(api.postAsInternalBff(link.userId(), link.path() + "/approve")));
        List<String> exchanges = List.of(
                        awaitPublished(output, approveTraceId, "partner-link.approved.v1", "partnerlink.events"),
                        awaitPublished(output, approveTraceId, "partner-link.approved.v1", "partnerlink.webhooks"))
                .stream()
                .map(entry -> entry.get("messaging").get("exchange").asString())
                .toList();
        assertThat(exchanges).containsExactlyInAnyOrder("partnerlink.events", "partnerlink.webhooks");

        assertNoSecrets(output.getAll(), email, alice, bff);
    }

    @Test
    void failedLookupsLogNeitherTheEmailNorTheOwlRequest(CapturedOutput output) {
        String rejected = OwlWireMockSupport.uniqueEmail();
        String unavailable = OwlWireMockSupport.uniqueEmail();
        owl.status(rejected, 401);
        owl.status(unavailable, 503);

        scenarios.awaitLookup(api.submit(partner, rejected));
        scenarios.awaitLookup(api.submit(partner, unavailable));

        awaitEntry(output, entry -> "user lookup rejected by Owl".equals(text(entry, "message")));
        awaitEntry(output, entry -> "user lookup failed after retries".equals(text(entry, "message")));
        assertNoSecrets(output.getAll(), rejected, null, null);
        assertNoSecrets(output.getAll(), unavailable, null, null);
    }

    private JsonNode awaitPublished(CapturedOutput output, String traceId, String routingKey) {
        return awaitPublished(output, traceId, routingKey, null);
    }

    private JsonNode awaitPublished(CapturedOutput output, String traceId, String routingKey, String exchange) {
        JsonNode published = awaitEntry(
                output,
                entry -> "message published".equals(text(entry, "message"))
                        && traceId.equals(traceIdOf(entry))
                        && routingKey.equals(
                                entry.get("messaging").get("routingKey").asString())
                        && (exchange == null
                                || exchange.equals(
                                        entry.get("messaging").get("exchange").asString())));
        assertThat(published.get("messaging").get("operation").asString()).isEqualTo("publish");
        assertThat(published.get("messaging").get("messageId").asString()).isNotBlank();
        assertThat(published.get("trace").get("correlationId").asString()).isNotBlank();
        return published;
    }

    private JsonNode awaitEntry(CapturedOutput output, Predicate<JsonNode> matching) {
        await().atMost(ASYNC).until(() -> entries(output).stream().anyMatch(matching));
        return entries(output).stream().filter(matching).findFirst().orElseThrow();
    }

    private List<JsonNode> entries(CapturedOutput output) {
        return output.getAll()
                .lines()
                .filter(line -> line.startsWith("{"))
                .map(jsonMapper::readTree)
                .toList();
    }

    private static void assertNoSecrets(String logs, String email, String userToken, String bffToken) {
        assertThat(logs)
                .doesNotContain(email)
                .doesNotContain(email.replace("@", "%40"))
                .doesNotContain("email=")
                .doesNotContain(OwlWireMockSupport.USER_PATH)
                .doesNotContain(InfrastructureContainers.OWL_API_KEY)
                .doesNotContain(InfrastructureContainers.OWL_CLIENT_SECRET)
                .doesNotContain(OwlWireMockSupport.ACCESS_TOKEN);
        if (userToken != null) {
            assertThat(logs).doesNotContain(userToken).doesNotContain(bffToken);
        }
    }

    private static String traceIdOf(JsonNode entryOrResponse) {
        JsonNode holder = entryOrResponse.has("meta") ? entryOrResponse.get("meta") : entryOrResponse.get("trace");
        return holder == null || holder.get("traceId") == null
                ? null
                : holder.get("traceId").asString();
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null ? null : value.asString();
    }
}
