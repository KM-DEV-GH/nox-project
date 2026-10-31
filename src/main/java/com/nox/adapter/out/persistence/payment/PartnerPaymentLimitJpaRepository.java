package com.nox.adapter.out.persistence.payment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerPaymentLimitJpaRepository
        extends JpaRepository<PartnerPaymentLimitEntity, PartnerPaymentLimitEntity.Key> {}
