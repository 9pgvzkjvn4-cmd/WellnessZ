package com.wellnessz.casekernel.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * SPI implemented by claims & cost tracking (E08-F05) so that treatment coordination (E04 pre-authorisation estimates and
 * provider invoices) posts case costs into one ledger rolled up by subsidiary / department / cost centre. Amounts only –
 * never clinical detail.
 */
public interface CaseCostRecorder {

    enum Category { MEDICAL, REHAB, INDEMNITY, ADMIN, SETTLEMENT, LEGAL }

    enum Kind { ESTIMATE, INVOICE, PAYMENT, RESERVE }

    /**
     * @param sourceType originating object, e.g. {@code PREAUTH}, {@code PROVIDER_INVOICE}
     * @param sourceRef  originating object id – the entry is idempotent on (sourceType, sourceRef, kind)
     */
    record CostEntry(UUID caseId, String employeeId, Category category, Kind kind, BigDecimal amount, String currency, LocalDate incurredOn,
                     String providerCode, String description, String sourceType, String sourceRef) {
    }

    /** Records (or updates) the entry; returns its ledger id. */
    UUID record(String actor, CostEntry entry);
}
