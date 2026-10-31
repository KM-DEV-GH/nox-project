package com.nox.application.port.out.partnerlink;

/** Finds the Owl user for an email (contracts/owl-user-lookup.md). Never throws for lookup failures. */
public interface UserLookupPort {

    UserLookupResult lookUpByEmail(String email);
}
