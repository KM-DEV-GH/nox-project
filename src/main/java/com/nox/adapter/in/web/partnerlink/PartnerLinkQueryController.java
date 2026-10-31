package com.nox.adapter.in.web.partnerlink;

import com.nox.adapter.in.web.error.ProblemException;
import com.nox.adapter.in.web.error.ProblemType;
import com.nox.adapter.in.web.response.ApiResponse;
import com.nox.adapter.in.web.response.MetaFactory;
import com.nox.application.port.in.partnerlink.GetPartnerLinkForPartnerUseCase;
import com.nox.domain.partner.Partner;
import com.nox.domain.partnerlink.PartnerLink;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A partner's reads of its established links; anything else is the same 404 (research.md decision 9). */
@RestController
@RequestMapping("/api/v1/partner-links")
public class PartnerLinkQueryController {

    private final GetPartnerLinkForPartnerUseCase partnerLinks;
    private final MetaFactory metaFactory;

    public PartnerLinkQueryController(GetPartnerLinkForPartnerUseCase partnerLinks, MetaFactory metaFactory) {
        this.partnerLinks = partnerLinks;
        this.metaFactory = metaFactory;
    }

    @GetMapping("/{linkId}")
    ApiResponse<PartnerLinkResponse> byId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable UUID linkId,
            HttpServletRequest request) {
        return respond(partnerLinks.byId(partner.id(), linkId), request);
    }

    @GetMapping("/by-external/{externalId}")
    ApiResponse<PartnerLinkResponse> byExternalId(
            @RequestAttribute(PartnerCallerResolver.PARTNER_ATTRIBUTE) Partner partner,
            @PathVariable String externalId,
            HttpServletRequest request) {
        return respond(partnerLinks.byExternalId(partner.id(), externalId), request);
    }

    private ApiResponse<PartnerLinkResponse> respond(Optional<PartnerLink> link, HttpServletRequest request) {
        PartnerLink found = link.orElseThrow(() -> new ProblemException(ProblemType.NOT_FOUND));
        return new ApiResponse<>(PartnerLinkResponse.of(found), metaFactory.create(request));
    }
}
