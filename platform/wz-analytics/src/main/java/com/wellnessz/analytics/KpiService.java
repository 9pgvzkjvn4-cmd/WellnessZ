package com.wellnessz.analytics;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * De-identified KPIs over the {@code analytics} schema views (which expose no names, no free text and no
 * restricted-class cases). All grouped outputs pass through {@link SmallCellSuppression}.
 */
@Service
public class KpiService {


    private final JdbcTemplate jdbc;
    private final int minGroupSize;

    public KpiService(JdbcTemplate jdbc, @Value("${wellnessz.analytics.min-group-size:5}") int minGroupSize) {
        if (minGroupSize < 3) {
            throw new IllegalArgumentException("Minimum group size below 3 is not permitted");
        }
        this.jdbc = jdbc;
        this.minGroupSize = minGroupSize;
    }

    public record KpiResult(String kpi, String groupBy, int minGroupSize, List<SmallCellSuppression.PublishedCell> cells) {
    }

    public KpiResult casesByStatus(String groupBy) {
        // one literal query per allow-listed dimension: user input never reaches the SQL text
        String sql = switch (groupBy) {
            case "orgUnit" -> "select org_unit_code as grp, status, count(*) as n from analytics.v_case_facts group by 1, 2 order by 1, 2";
            case "category" -> "select category as grp, status, count(*) as n from analytics.v_case_facts group by 1, 2 order by 1, 2";
            case "source" -> "select source as grp, status, count(*) as n from analytics.v_case_facts group by 1, 2 order by 1, 2";
            case "month" -> "select to_char(opened_month, 'YYYY-MM') as grp, status, count(*) as n from analytics.v_case_facts group by 1, 2 order by 1, 2";
            default -> throw new IllegalArgumentException("Unsupported dimension");
        };
        List<SmallCellSuppression.Cell> cells = jdbc.query(sql,
                (rs, i) -> new SmallCellSuppression.Cell(rs.getString("grp"), rs.getString("status"), rs.getLong("n")));
        return new KpiResult("cases-by-status", groupBy, minGroupSize, SmallCellSuppression.apply(cells, minGroupSize));
    }

    public int minGroupSize() {
        return minGroupSize;
    }
}
