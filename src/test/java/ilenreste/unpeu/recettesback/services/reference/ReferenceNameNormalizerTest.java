package ilenreste.unpeu.recettesback.services.reference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReferenceNameNormalizerTest {

    private final ReferenceNameNormalizer normalizer = new ReferenceNameNormalizer();

    @Test
    void collapsesTheFiveSpellingsThatWouldOtherwiseBecomeFiveRows() {
        // The concrete failure this column exists to prevent: without normalization the ingredients
        // table accepts all of these as distinct rows, and "one canonical row per real-world
        // ingredient" - the property that justified having a table at all - is dead within a week.
        assertThat(normalizer.normalize("Oeuf")).isEqualTo("oeuf");
        assertThat(normalizer.normalize("oeuf")).isEqualTo("oeuf");
        assertThat(normalizer.normalize(" oeuf ")).isEqualTo("oeuf");
        assertThat(normalizer.normalize("OEUF")).isEqualTo("oeuf");
    }

    @Test
    void stripsAccents_soTypingWithoutThemStillFindsTheRow() {
        assertThat(normalizer.normalize("Crème")).isEqualTo("creme");
        assertThat(normalizer.normalize("crème fraîche")).isEqualTo("creme fraiche");
        assertThat(normalizer.normalize("Piment d'Espelette")).isEqualTo("piment d'espelette");
    }

    @Test
    void collapsesInnerWhitespace() {
        assertThat(normalizer.normalize("sucre    glace")).isEqualTo("sucre glace");
        assertThat(normalizer.normalize("sucre\tglace")).isEqualTo("sucre glace");
    }

    @Test
    void keepsPluralsDistinct_deliberately() {
        // Stemming French correctly is a real NLP problem, and getting it wrong silently merges
        // unrelated ingredients. A human noticing a duplicate and an admin merging it is the
        // cheaper failure mode at this scale.
        assertThat(normalizer.normalize("oeufs")).isNotEqualTo(normalizer.normalize("oeuf"));
    }

    @Test
    void lowercasesWithARootLocale_notTheDefaultOne() {
        // Under a Turkish default locale, "I".toLowerCase() is a dotless "i" and the same name
        // normalizes differently depending on where the server happens to run.
        assertThat(normalizer.normalize("INDIVIDUEL")).isEqualTo("individuel");
    }
}
