package com.nox.application.fake;

import com.nox.application.port.out.partnerlink.UserLookupPort;
import com.nox.application.port.out.partnerlink.UserLookupResult;
import java.util.ArrayList;
import java.util.List;

public class StubUserLookup implements UserLookupPort {

    private final List<String> lookedUp = new ArrayList<>();
    private UserLookupResult result = new UserLookupResult.NotFound();

    public void willReturn(UserLookupResult result) {
        this.result = result;
    }

    public List<String> lookedUp() {
        return List.copyOf(lookedUp);
    }

    @Override
    public UserLookupResult lookUpByEmail(String email) {
        lookedUp.add(email);
        return result;
    }
}
