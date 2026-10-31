package com.nox.adapter.in.web.partnerlink;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract {@code CreatePartnerLinkRequest}. Unknown properties are rejected ({@code additionalProperties: false}). */
public record CreatePartnerLinkRequest(
        @NotBlank @Size(max = 128) String externalId,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 64) String licenseeId) {}
