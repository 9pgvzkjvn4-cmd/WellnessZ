package com.wellnessz.e03.terminology;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TerminologyCatalogTest {

    @Test
    void queryBecomesAPrefixTsQueryOfLettersAndDigitsOnly() {
        assertThat(TerminologyCatalog.toPrefixQuery("ankle")).isEqualTo("ankle:*");
        assertThat(TerminologyCatalog.toPrefixQuery("Low back")).isEqualTo("low:* & back:*");
        assertThat(TerminologyCatalog.toPrefixQuery("'; drop table x; --")).isEqualTo("drop:* & table:* & x:*");
        assertThat(TerminologyCatalog.toPrefixQuery("a:* | !b & (c)")).isEqualTo("a:* & b:* & c:*");
        assertThat(TerminologyCatalog.toPrefixQuery("  ;; ")).isNull();
        assertThat(TerminologyCatalog.toPrefixQuery(null)).isNull();
        assertThat(TerminologyCatalog.toPrefixQuery("1 2 3 4 5 6 7 8 9 10")).isEqualTo("1:* & 2:* & 3:* & 4:* & 5:* & 6:* & 7:* & 8:*");
    }

    @Test
    void codePrefixOnlyForCodeLikeInput() {
        assertThat(TerminologyCatalog.codePrefix("s93")).isEqualTo("S93%");
        assertThat(TerminologyCatalog.codePrefix("28539-")).isEqualTo("28539-%");
        assertThat(TerminologyCatalog.codePrefix("low back")).isNull();
        assertThat(TerminologyCatalog.codePrefix("S9%")).isNull();
        assertThat(TerminologyCatalog.codePrefix("S9_")).isNull();
        assertThat(TerminologyCatalog.codePrefix("")).isNull();
    }
}
