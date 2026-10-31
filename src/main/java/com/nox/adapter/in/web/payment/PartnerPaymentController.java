package com.nox.adapter.in.web.payment;

import com.nox.adapter.in.web.partnerlink.PartnerCallerResolver;
import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.payment.CancelPaymentUseCase;
import com.nox.application.port.in.payment.ConfirmPaymentUseCase;
import com.nox.application.port.in.payment.InitiatePaymentUseCase;
import com.nox.application.port.in.payment.InitiatePaymentUseCase.Initiation;
import com.nox.application.port.in.payment.LinkReference;
import com.nox.domain.partner.Partner;
import com.nox.domain.payment.Payment;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partner payments on a user's link, named by NOX's {@code userLinkId} or the partner's own
 * {@code externalLinkId} (research.md D1, D9). The calling partner was resolved by
 * {@link PartnerCallerResolver}; partner, account and user identities never come from the body.
 * Failures are {@code PaymentException}s, written as problems by the central handler.
 */
@RestController
@RequestMapping("/api/v1/user-partner-links")
public class PartnerPaymentController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final InitiatePaymentUseCase initiate;
    private final ConfirmPaymentUseCase confirm;
    private final CancelPaymentUseCase cancel;
    private final MetaFactory metaFactory;

    public PartnerPaymentController(
            InitiatePaymentUseCase initiate,
            ConfirmPaymentUseCase confirm,
            CancelPaymentUseCase cancel,
            MetaFactory metaFactory) {
        this.initiate = initiate;
        this.confirm = confirm;
        this.cancel = cancel;
        this.metaFactory = metaFactory;
    }

    /** {@code 201} for a new payment, {@code 200} for an identical retry (FR-008). */
    @PostMapping("/{userLinkId}/payments")
    ResponseEntity<ApiResponse<PaymentResponse>> initiateByLinkId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable UUID userLinkId,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PaymentInitiationRequest body,
            HttpServletRequest request) {
        return initiate(partner, new LinkReference.ById(userLinkId), idempotencyKey, body, request);
    }

    @PostMapping("/by-external-id/{externalLinkId}/payments")
    ResponseEntity<ApiResponse<PaymentResponse>> initiateByExternalLinkId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable @Size(max = 128) String externalLinkId,
            @RequestHeader(IDEMPOTENCY_KEY) @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PaymentInitiationRequest body,
            HttpServletRequest request) {
        return initiate(partner, new LinkReference.ByExternalId(externalLinkId), idempotencyKey, body, request);
    }

    /** Only a payment of this connected link and partner can be confirmed (FR-014). */
    @PostMapping("/{userLinkId}/payments/{paymentId}/confirmation")
    ApiResponse<PaymentResponse> confirmByLinkId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable UUID userLinkId,
            @PathVariable @Size(max = 128) String paymentId,
            @Valid @RequestBody PaymentConfirmationRequest body,
            HttpServletRequest request) {
        return confirm(partner, new LinkReference.ById(userLinkId), paymentId, body, request);
    }

    @PostMapping("/by-external-id/{externalLinkId}/payments/{paymentId}/confirmation")
    ApiResponse<PaymentResponse> confirmByExternalLinkId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable @Size(max = 128) String externalLinkId,
            @PathVariable @Size(max = 128) String paymentId,
            @Valid @RequestBody PaymentConfirmationRequest body,
            HttpServletRequest request) {
        return confirm(partner, new LinkReference.ByExternalId(externalLinkId), paymentId, body, request);
    }

    /** Only a payment of this connected link and partner that awaits the OTP can be cancelled (FR-020, FR-021). */
    @PostMapping("/{userLinkId}/payments/{paymentId}/cancellation")
    ApiResponse<PaymentResponse> cancelByLinkId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable UUID userLinkId,
            @PathVariable @Size(max = 128) String paymentId,
            HttpServletRequest request) {
        return cancel(partner, new LinkReference.ById(userLinkId), paymentId, request);
    }

    @PostMapping("/by-external-id/{externalLinkId}/payments/{paymentId}/cancellation")
    ApiResponse<PaymentResponse> cancelByExternalLinkId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable @Size(max = 128) String externalLinkId,
            @PathVariable @Size(max = 128) String paymentId,
            HttpServletRequest request) {
        return cancel(partner, new LinkReference.ByExternalId(externalLinkId), paymentId, request);
    }

    private ApiResponse<PaymentResponse> cancel(
            Partner partner, LinkReference link, String paymentId, HttpServletRequest request) {
        return respond(cancel.cancel(new CancelPaymentUseCase.Command(partner.id(), link, paymentId)), request);
    }

    private ApiResponse<PaymentResponse> confirm(
            Partner partner,
            LinkReference link,
            String paymentId,
            PaymentConfirmationRequest body,
            HttpServletRequest request) {
        Payment payment =
                confirm.confirm(new ConfirmPaymentUseCase.Command(partner.id(), link, paymentId, body.code()));
        return respond(payment, request);
    }

    private ResponseEntity<ApiResponse<PaymentResponse>> initiate(
            Partner partner,
            LinkReference link,
            String idempotencyKey,
            PaymentInitiationRequest body,
            HttpServletRequest request) {
        Initiation initiation = initiate.initiate(new InitiatePaymentUseCase.Command(
                partner.id(),
                link,
                idempotencyKey,
                body.type(),
                body.money().toMoney(),
                body.externalId(),
                body.transactionReference(),
                body.description()));
        return ResponseEntity.status(initiation.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(respond(initiation.payment(), request));
    }

    private ApiResponse<PaymentResponse> respond(Payment payment, HttpServletRequest request) {
        return new ApiResponse<>(PaymentResponse.of(payment), metaFactory.create(request));
    }
}
