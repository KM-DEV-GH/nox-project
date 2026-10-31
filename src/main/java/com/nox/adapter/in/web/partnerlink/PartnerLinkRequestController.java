package com.nox.adapter.in.web.partnerlink;

import com.nox.adapter.in.web.error.ProblemException;
import com.nox.adapter.in.web.error.ProblemType;
import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.partnerlink.GetPartnerLinkRequestStatusUseCase;
import com.nox.application.port.in.partnerlink.SubmitPartnerLinkRequestUseCase;
import com.nox.domain.partner.Partner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Link requests submitted by the BFF for a partner. The calling partner was resolved by
 * {@link PartnerCallerResolver} before the request got here.
 */
@RestController
@RequestMapping("/api/v1/partner-link-requests")
public class PartnerLinkRequestController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final SubmitPartnerLinkRequestUseCase submit;
    private final GetPartnerLinkRequestStatusUseCase requestStatus;
    private final MetaFactory metaFactory;

    public PartnerLinkRequestController(
            SubmitPartnerLinkRequestUseCase submit,
            GetPartnerLinkRequestStatusUseCase requestStatus,
            MetaFactory metaFactory) {
        this.submit = submit;
        this.requestStatus = requestStatus;
        this.metaFactory = metaFactory;
    }

    /** Always {@code 202} for a valid request, before any user lookup (FR-001). */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    ApiResponse<LinkRequestAcceptedResponse> create(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreatePartnerLinkRequest body,
            HttpServletRequest request) {
        UUID requestId = submit.submit(new SubmitPartnerLinkRequestUseCase.Command(
                partner.id(), idempotencyKey, body.externalId(), body.email(), body.licenseeId()));
        return new ApiResponse<>(LinkRequestAcceptedResponse.of(requestId), metaFactory.create(request));
    }

    /** Fallback status check: {@code PROCESSING} until a reportable outcome (FR-012). */
    @GetMapping("/{requestId}")
    ApiResponse<LinkRequestStatusResponse> status(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable UUID requestId,
            HttpServletRequest request) {
        LinkRequestStatusResponse status = requestStatus
                .status(partner.id(), requestId)
                .map(reported -> new LinkRequestStatusResponse(requestId, reported))
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND));
        return new ApiResponse<>(status, metaFactory.create(request));
    }
}
