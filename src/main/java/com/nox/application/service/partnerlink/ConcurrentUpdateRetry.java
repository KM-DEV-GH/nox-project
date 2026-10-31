package com.nox.application.service.partnerlink;

import com.nox.application.port.out.partnerlink.ConcurrentPartnerLinkUpdateException;
import java.util.function.Supplier;

/**
 * Retries a transactional unit of work once when another transaction changed the link first
 * (research.md decision 4). The retry re-reads the link, so the domain rule sees the winner's state.
 * The unit must run in its own transaction: the losing one is rolled back.
 */
final class ConcurrentUpdateRetry {

    private ConcurrentUpdateRetry() {}

    static <T> T once(Supplier<T> unitOfWork) {
        try {
            return unitOfWork.get();
        } catch (ConcurrentPartnerLinkUpdateException e) {
            return unitOfWork.get();
        }
    }
}
