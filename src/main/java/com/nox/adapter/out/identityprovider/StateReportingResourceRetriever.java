package com.nox.adapter.out.identityprovider;

import com.nimbusds.jose.util.Resource;
import com.nimbusds.jose.util.ResourceRetriever;
import java.io.IOException;
import java.net.URL;

/** Reports the outcome of every key set fetch to the {@link SigningKeyStateTracker}. */
class StateReportingResourceRetriever implements ResourceRetriever {

    private final ResourceRetriever delegate;
    private final SigningKeyStateTracker tracker;

    StateReportingResourceRetriever(ResourceRetriever delegate, SigningKeyStateTracker tracker) {
        this.delegate = delegate;
        this.tracker = tracker;
    }

    @Override
    public Resource retrieveResource(URL url) throws IOException {
        try {
            Resource resource = delegate.retrieveResource(url);
            tracker.recordSuccess();
            return resource;
        } catch (IOException e) {
            tracker.recordFailure();
            throw e;
        }
    }
}
