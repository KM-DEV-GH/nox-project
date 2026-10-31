package com.nox.application.port.in.partnerlink;

import com.nox.domain.partnerlink.LinkDecisionOutcome;

/** What a user's action did, and the link afterwards. */
public record LinkDecision(LinkDecisionOutcome outcome, UserPartnerLink link) {}
