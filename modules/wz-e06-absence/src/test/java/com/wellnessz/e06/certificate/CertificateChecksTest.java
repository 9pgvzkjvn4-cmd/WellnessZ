package com.wellnessz.e06.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CertificateChecksTest {

    static final LocalDate FROM = LocalDate.of(2026, 10, 19);
    static final LocalDate TO = LocalDate.of(2026, 10, 25);

    @Test
    void extractsIssuerRegistrationAndDatesFromText() {
        CertificateChecks.Extracted x = CertificateChecks.extract("""
                Statement of Fitness for Work
                Issued by: Dr Jane Smith
                GMC 7012345
                Issue date: 2026-10-20
                Not fit for work from 19/10/2026 to 25.10.2026""");
        assertThat(x.issuerName()).isEqualTo("Dr Jane Smith");
        assertThat(x.registration()).isEqualTo("7012345");
        assertThat(x.issueDate()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(x.fromDate()).isEqualTo(FROM);
        assertThat(x.toDate()).isEqualTo(TO);
        assertThat(CertificateChecks.Extracted.blank(CertificateChecks.extract(""))).isTrue();
        assertThat(CertificateChecks.extract("from 31/02/2026").fromDate()).isNull();
    }

    @Test
    void duplicateBySha256CertificateNumberOrSameEmployeeAndDates() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<CertificateChecks.Known> known = List.of(
                new CertificateChecks.Known(a, "E1", "sha-a", null, null, FROM.minusDays(30), TO.minusDays(30)),
                new CertificateChecks.Known(b, "E2", null, "GMC1", "NUM-9", FROM.minusDays(60), TO.minusDays(60)),
                new CertificateChecks.Known(c, "E1", null, null, null, FROM, TO));
        assertThat(CertificateChecks.duplicateOf(new CertificateChecks.Candidate("E9", "sha-a", null, null, FROM, TO), known)).contains(a);
        assertThat(CertificateChecks.duplicateOf(new CertificateChecks.Candidate("E9", "sha-x", "gmc1", "num-9", FROM, TO), known)).contains(b);
        assertThat(CertificateChecks.duplicateOf(new CertificateChecks.Candidate("E9", "sha-x", "GMC2", "NUM-9", FROM, TO), known)).isEmpty();
        assertThat(CertificateChecks.duplicateOf(new CertificateChecks.Candidate("E1", "sha-x", null, null, FROM, TO), known)).contains(c);
        assertThat(CertificateChecks.duplicateOf(new CertificateChecks.Candidate("E1", "sha-x", null, null, FROM, TO.plusDays(1)), known)).isEmpty();
    }

    @Test
    void alterationFlags() {
        CertificateChecks.Extracted same = new CertificateChecks.Extracted("Dr A", FROM, FROM, TO, "123");
        assertThat(CertificateChecks.alterationFlags(FROM, TO, null, same, true, null)).isEmpty();
        CertificateChecks.Extracted other = new CertificateChecks.Extracted("Dr A", FROM, FROM, TO.plusDays(7), "123");
        assertThat(CertificateChecks.alterationFlags(FROM, TO, null, other, true, null)).containsExactly(CertificateChecks.DATES_DIFFER_FROM_OCR);
        assertThat(CertificateChecks.alterationFlags(FROM, TO, FROM.plusDays(8), null, null, null))
                .containsExactly(CertificateChecks.ISSUE_AFTER_START_PLUS_7);
        assertThat(CertificateChecks.alterationFlags(FROM, TO, FROM.plusDays(7), null, null, null)).isEmpty();
        assertThat(CertificateChecks.alterationFlags(FROM, TO, null, null, false, null)).containsExactly(CertificateChecks.UNREADABLE);
        byte[] edited = "%PDF-1.4\n/CreationDate (D:20261019090000Z) /ModDate (D:20261023120000Z)\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        byte[] fresh = "%PDF-1.4\n/CreationDate (D:20261019090000Z) /ModDate (D:20261019090030Z)\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        byte[] incremental = "%PDF-1.4\nobj\n%%EOF\nmore objects\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(CertificateChecks.alterationFlags(FROM, TO, null, same, true, edited)).containsExactly(CertificateChecks.PDF_MODIFIED_AFTER_CREATION);
        assertThat(CertificateChecks.alterationFlags(FROM, TO, null, same, true, fresh)).isEmpty();
        assertThat(CertificateChecks.pdfModifiedAfterCreation(incremental)).isTrue();
    }
}
