package com.wellnessz.e08.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;

/** Disclosure-set rules (allowed types, OH-only types, never-disclosed types, privilege, restricted class) and redaction. */
class DisclosureRulesTest {

    static DisclosureRules.Candidate doc(String type) {
        return new DisclosureRules.Candidate(type, "E1", true, false, false);
    }

    @Test
    void allowedTypesMayBeAddedByTheClaimsTeam() {
        for (String type : DisclosureRules.ALLOWED_TYPES) {
            assertThat(DisclosureRules.refusal(doc(type), "E1", false)).as(type).isEmpty();
        }
    }

    @Test
    void providerAndExternalReportsOnlyByOh() {
        assertThat(DisclosureRules.refusal(doc("PROVIDER_REPORT"), "E1", false)).isPresent();
        assertThat(DisclosureRules.refusal(doc("EXTERNAL_REPORT"), "E1", false)).isPresent();
        assertThat(DisclosureRules.refusal(doc("PROVIDER_REPORT"), "E1", true)).isEmpty();
        assertThat(DisclosureRules.refusal(doc("EXTERNAL_REPORT"), "E1", true)).isEmpty();
    }

    @Test
    void neverDisclosedTypesEvenByOh() {
        assertThat(DisclosureRules.refusal(doc("MEDICO_LEGAL_REPORT"), "E1", true)).isPresent();
        assertThat(DisclosureRules.refusal(doc("PHOTO"), "E1", true)).isPresent();
        assertThat(DisclosureRules.refusal(doc("RESULT_LETTER"), "E1", true)).isPresent();
        assertThat(DisclosureRules.refusal(doc("SICK_CERTIFICATE"), "E1", false)).isPresent();
    }

    @Test
    void privilegedRestrictedUnsignedOrForeignDocumentsAreRefused() {
        assertThat(DisclosureRules.refusal(new DisclosureRules.Candidate("FITNESS_CERTIFICATE", "E1", true, false, true), "E1", true))
                .hasValueSatisfying(r -> assertThat(r).contains("privileged"));
        assertThat(DisclosureRules.refusal(new DisclosureRules.Candidate("PROVIDER_REPORT", "E1", true, true, false), "E1", true))
                .hasValueSatisfying(r -> assertThat(r).contains("restricted"));
        assertThat(DisclosureRules.refusal(new DisclosureRules.Candidate("FITNESS_CERTIFICATE", "E1", false, false, false), "E1", false))
                .hasValueSatisfying(r -> assertThat(r).contains("signed"));
        assertThat(DisclosureRules.refusal(doc("FITNESS_CERTIFICATE"), "E2", false))
                .hasValueSatisfying(r -> assertThat(r).contains("employee"));
    }

    // ------------------------------------------------------------------ redaction

    @Test
    void phrasesAreMaskedCaseInsensitivelyAndLiterally() {
        Redaction.Result r = Redaction.mask("Lumbar strain after lifting. LUMBAR STRAIN improving. Cost (a+b) noted.",
                List.of("lumbar strain", "(a+b)"));
        assertThat(r.text()).isEqualTo("[REDACTED] after lifting. [REDACTED] improving. Cost [REDACTED] noted.");
        assertThat(r.replacements()).isEqualTo(3);
    }

    @Test
    void longerPhrasesWinAndMasksAreNotReMatched() {
        Redaction.Result r = Redaction.mask("History of knee surgery in 2019.", List.of("knee", "knee surgery in 2019", "red"));
        assertThat(r.text()).isEqualTo("History of [REDACTED].");
        assertThat(r.replacements()).isEqualTo(1);
    }

    @Test
    void phraseValidation() {
        assertThat(Redaction.validate(List.of(" knee ", "knee", "back"))).containsExactly("knee", "back");
        assertThatThrownBy(() -> Redaction.validate(List.of())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Redaction.validate(List.of("x"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Redaction.validate(Collections.nCopies(51, "phrase"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Redaction.validate(List.of("a".repeat(201)))).isInstanceOf(ApiException.class);
    }
}
