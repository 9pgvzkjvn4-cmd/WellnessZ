package com.wellnessz.e31.terminology;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Terminology service of the engine: loads the active release and the reference tables of {@code deviceint} into an
 * immutable snapshot, refreshed every ten minutes (versioned reference data, contract section 0).
 */
@Service("e31TerminologyService")
public class TerminologyService implements Terminology {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile TerminologySnapshot snapshot;
    private volatile Instant loadedAt = Instant.EPOCH;

    public TerminologyService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public TerminologySnapshot snapshot() {
        Instant now = clock.instant();
        TerminologySnapshot s = snapshot;
        if (s == null || loadedAt.plusSeconds(600).isBefore(now)) {
            synchronized (this) {
                if (snapshot == null || loadedAt.plusSeconds(600).isBefore(now)) {
                    snapshot = load();
                    loadedAt = now;
                }
                s = snapshot;
            }
        }
        return s;
    }

    public synchronized void reload() {
        snapshot = load();
        loadedAt = clock.instant();
    }

    TerminologySnapshot load() {
        String release = jdbc.query("select version from deviceint.terminology_release where active order by loaded_at desc limit 1",
                rs -> rs.next() ? rs.getString(1) : "NONE");
        List<Entry> entries = jdbc.query("""
                select source_system, source_code, mdc_code, loinc_code, snomed_code, display, ucum
                  from deviceint.terminology_map where release_version = ? order by source_system, source_code""",
                (rs, i) -> new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7)), release);
        Map<String, BigDecimal[]> conv = new HashMap<>();
        jdbc.query("select from_unit, to_unit, factor, shift from deviceint.unit_conversion", rs -> {
            conv.put(TerminologySnapshot.conversionKey(rs.getString(1), rs.getString(2)), new BigDecimal[] {rs.getBigDecimal(3), rs.getBigDecimal(4)});
        });
        List<TerminologySnapshot.RangeRow> ranges = jdbc.query(
                "select loinc_code, sex, age_min, age_max, low, high, text from deviceint.reference_range order by loinc_code, sex desc",
                (rs, i) -> new TerminologySnapshot.RangeRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getString(7)));
        Map<String, List<Gli2012.SplinePoint>> splines = new HashMap<>();
        jdbc.query("select parameter, sex, age, m_spline, s_spline from deviceint.predicted_spline where equation = 'GLI_2012'", rs -> {
            splines.computeIfAbsent(rs.getString(1) + "|" + rs.getString(2), k -> new ArrayList<>())
                    .add(new Gli2012.SplinePoint(rs.getDouble(3), rs.getDouble(4), rs.getDouble(5)));
        });
        List<Gli2012.Coefficients> gli = jdbc.query("""
                select parameter, sex, l_a0, l_a1, m_a0, m_a1, m_a2, s_a0, s_a1 from deviceint.predicted_equation where equation = 'GLI_2012'""",
                (rs, i) -> new Gli2012.Coefficients(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9),
                        splines.getOrDefault(rs.getString(1) + "|" + rs.getString(2), List.of())));
        List<OshaAgeCorrection.Row> osha = jdbc.query("select sex, age, hz1000, hz2000, hz3000, hz4000, hz6000 from deviceint.osha_age_correction",
                (rs, i) -> new OshaAgeCorrection.Row(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7)));
        return new TerminologySnapshot(release, entries, conv, ranges, gli, osha);
    }

    public List<Map<String, Object>> versions() {
        return jdbc.queryForList("select version, description, active, loaded_at as \"loadedAt\" from deviceint.terminology_release order by loaded_at desc");
    }

    @Override
    public String releaseVersion() {
        return snapshot().releaseVersion();
    }

    @Override
    public boolean known(String code) {
        return snapshot().known(code);
    }

    @Override
    public Optional<Entry> byLoinc(String loinc) {
        return snapshot().byLoinc(loinc);
    }

    @Override
    public Optional<Entry> byMdc(String mdc) {
        return snapshot().byMdc(mdc);
    }

    @Override
    public Optional<BigDecimal> convert(BigDecimal value, String fromUnit, String toUnit) {
        return snapshot().convert(value, fromUnit, toUnit);
    }

    @Override
    public Optional<Range> range(String loinc, String sex, Integer ageYears) {
        return snapshot().range(loinc, sex, ageYears);
    }

    @Override
    public Optional<Gli2012.Coefficients> gli(String parameter, String sex) {
        return snapshot().gli(parameter, sex);
    }

    @Override
    public Optional<OshaAgeCorrection.Row> oshaRow(String sex, int age) {
        return snapshot().oshaRow(sex, age);
    }

    @Override
    public List<Entry> entries(String system, String code) {
        return snapshot().entries(system, code);
    }
}
