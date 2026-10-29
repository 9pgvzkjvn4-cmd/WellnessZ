package com.wellnessz.e03.cds;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/** {@code 409 code=cds_alert} with {@code alerts:[CdsAlert]}: repeat the request with an override reason for every alert. */
public class CdsAlertException extends ApiException {

    private static final long serialVersionUID = 1L;
    private final transient List<CdsAlert> alerts;

    public CdsAlertException(List<CdsAlert> alerts) {
        super(HttpStatus.CONFLICT, "cds_alert", "Clinical decision support alerts require an override reason");
        this.alerts = List.copyOf(alerts);
    }

    public List<CdsAlert> alerts() {
        return alerts;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("alerts", alerts);
    }
}
