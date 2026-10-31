package com.nox.adapter.in.web.partnerlink;

import com.nox.adapter.in.web.error.ProblemException;
import com.nox.adapter.in.web.error.ProblemType;
import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.partnerlink.ApprovePartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.GetMyPartnerLinkHistoryUseCase;
import com.nox.application.port.in.partnerlink.GetMyPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.LinkDecision;
import com.nox.application.port.in.partnerlink.ListMyPartnerLinksUseCase;
import com.nox.application.port.in.partnerlink.RejectPartnerLinkUseCase;
import com.nox.application.port.in.partnerlink.RevokePartnerLinkUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A user's links, called by the internal BFF on the user's behalf (004 research.md D3, D6). The
 * user is the internal (Owl) id from {@code X-User-ID}; another user's link is reported as not
 * found.
 */
@RestController
@RequestMapping("/api/v1/user-partner-links")
public class PartnerLinkController {

    private final ListMyPartnerLinksUseCase list;
    private final GetMyPartnerLinkUseCase get;
    private final GetMyPartnerLinkHistoryUseCase history;
    private final ApprovePartnerLinkUseCase approve;
    private final RejectPartnerLinkUseCase reject;
    private final RevokePartnerLinkUseCase revoke;
    private final MetaFactory metaFactory;

    public PartnerLinkController(
            ListMyPartnerLinksUseCase list,
            GetMyPartnerLinkUseCase get,
            GetMyPartnerLinkHistoryUseCase history,
            ApprovePartnerLinkUseCase approve,
            RejectPartnerLinkUseCase reject,
            RevokePartnerLinkUseCase revoke,
            MetaFactory metaFactory) {
        this.list = list;
        this.get = get;
        this.history = history;
        this.approve = approve;
        this.reject = reject;
        this.revoke = revoke;
        this.metaFactory = metaFactory;
    }

    /** Every link of the user, with effective statuses (FR-014). */
    @GetMapping
    ApiResponse<List<UserLinkResponse>> list(@ActingUser UUID userId, HttpServletRequest request) {
        List<UserLinkResponse> links =
                list.list(userId.toString()).stream().map(UserLinkResponse::of).toList();
        return new ApiResponse<>(links, metaFactory.create(request));
    }

    @GetMapping("/{linkId}")
    ApiResponse<UserLinkResponse> get(@PathVariable UUID linkId, @ActingUser UUID userId, HttpServletRequest request) {
        UserLinkResponse link = get.get(linkId, userId.toString())
                .map(UserLinkResponse::of)
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND));
        return new ApiResponse<>(link, metaFactory.create(request));
    }

    /** Every written transition, oldest first (FR-009, FR-010). */
    @GetMapping("/{linkId}/history")
    ApiResponse<List<PartnerLinkHistoryEntryResponse>> history(
            @PathVariable UUID linkId, @ActingUser UUID userId, HttpServletRequest request) {
        List<PartnerLinkHistoryEntryResponse> entries = history.history(linkId, userId.toString())
                .map(found ->
                        found.stream().map(PartnerLinkHistoryEntryResponse::of).toList())
                .orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND));
        return new ApiResponse<>(entries, metaFactory.create(request));
    }

    @PostMapping("/{linkId}/approve")
    ApiResponse<UserLinkResponse> approve(
            @PathVariable UUID linkId, @ActingUser UUID userId, HttpServletRequest request) {
        return respond(approve.approve(linkId, userId.toString()), request);
    }

    @PostMapping("/{linkId}/reject")
    ApiResponse<UserLinkResponse> reject(
            @PathVariable UUID linkId, @ActingUser UUID userId, HttpServletRequest request) {
        return respond(reject.reject(linkId, userId.toString()), request);
    }

    /** Terminal; there is no "inactivate" action (FR-016). */
    @PostMapping("/{linkId}/revoke")
    ApiResponse<UserLinkResponse> revoke(
            @PathVariable UUID linkId, @ActingUser UUID userId, HttpServletRequest request) {
        return respond(revoke.revoke(linkId, userId.toString()), request);
    }

    private ApiResponse<UserLinkResponse> respond(Optional<LinkDecision> decision, HttpServletRequest request) {
        LinkDecision done = decision.orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND));
        return switch (done.outcome()) {
            case EXPIRED -> throw new ProblemException(ProblemType.LINK_EXPIRED);
            case NOT_ACTIONABLE -> throw new ProblemException(ProblemType.LINK_NOT_ACTIONABLE);
            case APPROVED, REJECTED, REVOKED ->
                new ApiResponse<>(UserLinkResponse.of(done.link()), metaFactory.create(request));
        };
    }
}
