package com.wellnessz.e03.cds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class CdsServiceTest {

    static final Map<String, String> CLASSES = Map.of("amoxicillin", "PENICILLIN", "penicillin", "PENICILLIN", "co-amoxiclav", "PENICILLIN",
            "ibuprofen", "NSAID", "warfarin", "ANTICOAGULANT");

    @Test
    void drugClassesAreMatchedOnWholeWords() {
        assertThat(CdsService.classesOf("Amoxicillin 500 mg capsules", CLASSES)).containsExactly("PENICILLIN");
        assertThat(CdsService.classesOf("Allergy to penicillin", CLASSES)).containsExactly("PENICILLIN");
        assertThat(CdsService.classesOf("Co-amoxiclav 625mg", CLASSES)).containsExactly("PENICILLIN");
        assertThat(CdsService.classesOf("Ibuprofen gel; warfarin", CLASSES)).containsExactlyInAnyOrder("NSAID", "ANTICOAGULANT");
        assertThat(CdsService.classesOf("Paracetamol", CLASSES)).isEmpty();
        assertThat(CdsService.classesOf("xamoxicillinx", CLASSES)).isEmpty();
        assertThat(CdsService.classesOf(null, CLASSES)).isEmpty();
    }

    @Test
    void tokensAreLowerCaseWordsWithHyphens() {
        assertThat(CdsService.tokens("Co-Amoxiclav 625 MG/5ml")).containsExactly("co-amoxiclav", "625", "mg", "5ml");
        assertThat(CdsService.tokens("x".repeat(600))).hasSize(1).allSatisfy(t -> assertThat(t).hasSize(500));
    }
}
