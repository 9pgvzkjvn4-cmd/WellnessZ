package com.wellnessz.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Statistical disclosure control for aggregate outputs:
 * <ol>
 *   <li><b>Primary suppression</b> – any cell with a count below the minimum group size {@code k} is hidden.</li>
 *   <li><b>Complementary suppression</b> – if exactly one cell is hidden, the next-smallest cell is hidden too,
 *       so the hidden value cannot be recovered by subtraction from the published total.</li>
 * </ol>
 */
public final class SmallCellSuppression {

    private SmallCellSuppression() {
    }

    public record Cell(String group, String category, long count) {
    }

    public record PublishedCell(String group, String category, Long count, boolean suppressed) {
    }

    public static List<PublishedCell> apply(List<Cell> cells, int k) {
        List<PublishedCell> out = new ArrayList<>();
        List<Cell> sorted = new ArrayList<>(cells);
        sorted.sort(Comparator.comparingLong(Cell::count));
        long primary = cells.stream().filter(c -> c.count() > 0 && c.count() < k).count();
        Cell complementary = null;
        if (primary == 1) {
            complementary = sorted.stream().filter(c -> c.count() >= k).findFirst().orElse(null);
        }
        for (Cell c : cells) {
            boolean suppress = (c.count() > 0 && c.count() < k) || c == complementary;
            out.add(new PublishedCell(c.group(), c.category(), suppress ? null : c.count(), suppress));
        }
        return out;
    }
}
