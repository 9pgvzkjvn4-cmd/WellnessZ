package com.wellnessz.casekernel.support;

import java.time.Clock;
import java.time.Year;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Case numbering {@code UNIT-YYYY-NNNNNN} (E01-F05) with a gap-tolerant per-unit, per-year counter. */
@Component
public class CaseRefGenerator {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CaseRefGenerator(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String next(String unitCode) {
        String unit = unitCode == null || !unitCode.matches("[A-Za-z0-9]{1,10}") ? "OH" : unitCode.toUpperCase(Locale.ROOT);
        int year = Year.now(clock).getValue();
        Long n = jdbc.queryForObject("""
                insert into casemgmt.case_sequence(unit_code, seq_year, last_value) values (?, ?, 1)
                on conflict (unit_code, seq_year) do update set last_value = casemgmt.case_sequence.last_value + 1
                returning last_value""", Long.class, unit, year);
        return unit + "-" + year + "-" + String.format(Locale.ROOT, "%06d", n);
    }
}
