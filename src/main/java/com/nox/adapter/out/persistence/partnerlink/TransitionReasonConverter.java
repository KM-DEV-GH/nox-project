package com.nox.adapter.out.persistence.partnerlink;

import com.nox.domain.partnerlink.TransitionReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores {@link TransitionReason} as its lower-case code, e.g. {@code user_approved}. */
@Converter
class TransitionReasonConverter implements AttributeConverter<TransitionReason, String> {

    @Override
    public String convertToDatabaseColumn(TransitionReason reason) {
        return reason == null ? null : reason.code();
    }

    @Override
    public TransitionReason convertToEntityAttribute(String code) {
        return code == null ? null : TransitionReason.ofCode(code);
    }
}
