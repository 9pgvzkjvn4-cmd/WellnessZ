package com.wellnessz.integration.terminology;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Local terminology cache. Production deployments load licensed releases (SNOMED CT national edition, ICD-10/11,
 * LOINC) via a batch loader or delegate to a terminology server (e.g. Ontoserver / Snowstorm) through
 * {@code $validate-code}. Licence terms of each code system must be respected.
 */
@Service
public class TerminologyService {

    private final JdbcTemplate jdbc;

    public TerminologyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Concept(String system, String code, String display) {
    }

    public Optional<Concept> lookup(CodeSystem system, String code) {
        List<Concept> rows = jdbc.query(
                "select system, code, display from platform.code_concept where system = ? and code = ? and active",
                (rs, i) -> new Concept(rs.getString(1), rs.getString(2), rs.getString(3)), system.name(), code);
        return rows.stream().findFirst();
    }

    public boolean isValid(CodeSystem system, String code) {
        return lookup(system, code).isPresent();
    }
}
