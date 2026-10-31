package com.nox.adapter.out.persistence.payment;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerPaymentAccountJpaRepository extends JpaRepository<PartnerPaymentAccountEntity, UUID> {}
