package com.wellnessz.e31.adapter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One record produced by an adapter's PARSE stage: the measured values keyed by the driver's field keys, the identity
 * found in the message, and transport values (technical fields). Deterministic for a given driver version.
 */
public record ParsedRecord(int index, Map<String, Value> values, Identity identity, Map<String, String> technical, List<String> notes) {

    public ParsedRecord {
        values = values == null ? Map.of() : Map.copyOf(values);
        technical = technical == null ? Map.of() : Map.copyOf(technical);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * @param type QUANTITY | STRING | CODE | BOOLEAN
     * @param unit the unit as reported by the device (or declared by the driver when the device reports none)
     */
    public record Value(String key, String type, String text, BigDecimal number, String unit) {
    }

    /**
     * Identity and context found in the message. {@code deviceTime} is the measurement time by the device clock (UTC).
     */
    public record Identity(String employeeId, String badgeId, String deviceSerial, String orderRef, Instant deviceTime, String name,
                           LocalDate dateOfBirth, String sex, Integer ageYears, BigDecimal heightCm, String operator, String sessionId,
                           String queueEntryId, String deviceInstanceId) {

        public static final Identity EMPTY = new Identity(null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        public Identity withEnvelope(String deviceInstanceId, String sessionId, String queueEntryId, String operator, Instant fallbackTime) {
            return new Identity(employeeId, badgeId, deviceSerial, orderRef, deviceTime == null ? fallbackTime : deviceTime, name, dateOfBirth, sex,
                    ageYears, heightCm, this.operator == null ? operator : this.operator, sessionId == null ? this.sessionId : sessionId,
                    queueEntryId == null ? this.queueEntryId : queueEntryId, deviceInstanceId == null ? this.deviceInstanceId : deviceInstanceId);
        }

        public Identity withEmployee(String employee) {
            return new Identity(employee, badgeId, deviceSerial, orderRef, deviceTime, name, dateOfBirth, sex, ageYears, heightCm, operator,
                    sessionId, queueEntryId, deviceInstanceId);
        }
    }
}
