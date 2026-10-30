package com.wellnessz.e10.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class SelectionEngineTest {

    private static final List<String> POOL = IntStream.rangeClosed(1, 40).mapToObj(i -> String.format("E%04d", i)).toList();

    @Test
    void sampleSizeIsCeilOfPoolTimesRateOverTwelve() {
        assertThat(SelectionEngine.sampleSize(120, new BigDecimal("25"))).isEqualTo(3);    // 120 × 25 % / 12 = 2.5 → 3
        assertThat(SelectionEngine.sampleSize(48, new BigDecimal("25"))).isEqualTo(1);     // 1.0 exactly
        assertThat(SelectionEngine.sampleSize(49, new BigDecimal("25"))).isEqualTo(2);     // 1.02 → 2
        assertThat(SelectionEngine.sampleSize(2, new BigDecimal("10"))).isEqualTo(1);      // small pools still tested
        assertThat(SelectionEngine.sampleSize(10, new BigDecimal("2000"))).isEqualTo(10);  // never more than the pool
        assertThat(SelectionEngine.sampleSize(0, new BigDecimal("25"))).isZero();
        assertThat(SelectionEngine.sampleSize(50, BigDecimal.ZERO)).isZero();
    }

    @Test
    void sameSeedAndSnapshotReproduceTheDrawExactly() {
        byte[] seed = SelectionEngine.newSeed(new SecureRandom());
        List<String> first = SelectionEngine.select(POOL, seed, 7);
        List<String> again = SelectionEngine.select(SelectionEngine.canonical(new ArrayList<>(POOL).reversed()), seed.clone(), 7);
        assertThat(first).hasSize(7).doesNotHaveDuplicates().isSubsetOf(POOL).isEqualTo(again);
        assertThat(SelectionEngine.poolHash(POOL)).isEqualTo(SelectionEngine.poolHash(SelectionEngine.canonical(POOL.reversed())));
        assertThat(SelectionEngine.commitment(seed)).isEqualTo(SelectionEngine.commitment(SelectionEngine.unhex(SelectionEngine.hex(seed))));
    }

    @Test
    void knownVectorMatchesTheDemoSelection() {
        byte[] seed = SelectionEngine.unhex("e10de0000000000000000000000000000000000000000000000000000000f001");
        assertThat(SelectionEngine.select(List.of("E1002", "E7001"), seed, 1)).containsExactly("E7001");
        assertThat(SelectionEngine.poolHash(List.of("E1002", "E7001"))).isEqualTo("fb2c944d1a25ecc419a437a5ff8b97517777ea8f1633d17014407f7aeae300a3");
        assertThat(SelectionEngine.commitment(seed)).isEqualTo("0a7de41ef4b012b1239ae780b9ffbf131d51ed5b8f9f7038db0165ef92e6d17f");
    }

    @Test
    void differentSeedsGiveDifferentDrawsAndADifferentPoolChangesTheHash() {
        SecureRandom r = new SecureRandom();
        HashSet<List<String>> draws = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            draws.add(SelectionEngine.select(POOL, SelectionEngine.newSeed(r), 5));
        }
        assertThat(draws).hasSizeGreaterThan(15);
        List<String> altered = new ArrayList<>(POOL);
        altered.set(3, "X9999");
        assertThat(SelectionEngine.poolHash(SelectionEngine.canonical(altered))).isNotEqualTo(SelectionEngine.poolHash(POOL));
    }

    @Test
    void everyMemberHasTheSameChance() {
        List<String> pool = List.of("A", "B", "C", "D", "E");
        Map<String, Integer> counts = new HashMap<>();
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < 10_000; i++) {
            counts.merge(SelectionEngine.select(pool, SelectionEngine.newSeed(r), 1).get(0), 1, Integer::sum);
        }
        assertThat(counts).hasSize(5);
        counts.values().forEach(c -> assertThat(c).isBetween(1_700, 2_300));
    }

    @Test
    void wholePoolDrawIsAPermutation() {
        byte[] seed = SelectionEngine.newSeed(new SecureRandom());
        assertThat(SelectionEngine.select(POOL, seed, 100)).hasSize(40).containsExactlyInAnyOrderElementsOf(POOL);
        assertThat(SelectionEngine.select(List.of(), seed, 3)).isEmpty();
    }
}
