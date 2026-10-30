package com.wellnessz.e09.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ContactCodesTest {

    @Test
    void codesHaveTheFormatAndUseTheUnambiguousAlphabet() {
        for (int i = 0; i < 500; i++) {
            String code = ContactCodes.generate();
            assertThat(code).matches("PS-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
            assertThat(ContactCodes.isValid(code)).isTrue();
            assertThat(code.substring(3)).doesNotContain("0", "O", "1", "I");
        }
    }

    @Test
    void codesAreRandomAndPracticallyUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            seen.add(ContactCodes.generate());
        }
        assertThat(seen).hasSizeGreaterThan(19_990);
    }

    @Test
    void codesCarryNothingOfTheRequester() {
        // the code depends on the random source only – the same seed gives the same code, whoever asks
        assertThat(ContactCodes.generate(new Random(42))).isEqualTo(ContactCodes.generate(new Random(42)));
        assertThat(ContactCodes.generate(new Random(1))).isNotEqualTo(ContactCodes.generate(new Random(2)));
    }

    @Test
    void employeeIdsAndFreeTextAreNotValidCodes() {
        assertThat(ContactCodes.isValid("E1005")).isFalse();
        assertThat(ContactCodes.isValid("PS-E100-5")).isFalse();
        assertThat(ContactCodes.isValid("ps-abcd-efgh")).isFalse();
        assertThat(ContactCodes.isValid(null)).isFalse();
        assertThat(ContactCodes.isValid("PS-DEMQ-7K2R")).isTrue();
    }
}
