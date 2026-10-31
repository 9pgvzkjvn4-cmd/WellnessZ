package com.wellnessz.e32.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e32.request.ModelRequestService;

class SupportTest {

    @Test
    void jsonDiffReportsChangedAddedAndRemovedPaths() {
        Map<String, Object> a = Docs.of("option", "P4", "parameters", Docs.of("mllp", Docs.of("port", 2575, "tls", "REQUIRED")), "old", 1);
        Map<String, Object> b = Docs.of("option", "P4", "parameters", Docs.of("mllp", Docs.of("port", 2576.0, "tls", "REQUIRED")), "new", List.of(1));
        List<Map<String, Object>> d = JsonDiff.diff(a, b);
        assertThat(d).extracting(m -> m.get("path")).containsExactlyInAnyOrder("parameters.mllp.port", "old", "new");
        assertThat(JsonDiff.diff(a, a)).isEmpty();
        assertThat(JsonDiff.diff(Docs.of("x", 1), Docs.of("x", 1.0))).isEmpty();
    }

    @Test
    void docsPathsMergeAndHashing() {
        Map<String, Object> m = Docs.of();
        Docs.put(m, "a.b.c", 3);
        assertThat(Docs.at(m, "a.b.c")).isEqualTo(3);
        assertThat(Docs.at(m, "a.x.c")).isNull();
        Map<String, Object> merged = Docs.merge(Docs.of("a", Docs.of("b", 1, "c", 2), "l", List.of(1, 2)), Docs.of("a", Docs.of("c", 9), "l", List.of(3)));
        assertThat(Docs.at(merged, "a.b")).isEqualTo(1);
        assertThat(Docs.at(merged, "a.c")).isEqualTo(9);
        assertThat(Docs.list(merged.get("l"))).containsExactly(3);
        assertThat(Docs.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Docs.of("a", null, "b", 2)).containsOnlyKeys("b");
        assertThat(Docs.csv(" P1, P2 ,,")).containsExactly("P1", "P2");
    }

    @Test
    void workingDaysSkipWeekends() {
        // Friday 30 Oct 2026 → Thursday 5 Nov 2026 = Mon, Tue, Wed, Thu = 4 working days
        assertThat(ModelRequestService.workingDays(Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-11-05T10:00:00Z"))).isEqualTo(4);
        assertThat(ModelRequestService.workingDays(Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-10-30T15:00:00Z"))).isZero();
    }
}
