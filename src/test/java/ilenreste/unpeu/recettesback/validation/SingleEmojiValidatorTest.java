package ilenreste.unpeu.recettesback.validation;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The emoji rule of docs/recipe-lists.md, case by case.
 * <p>
 * Every value is spelled with Unicode escapes where it is not self-evident what it is made of: a
 * test that says {@code "❤️"} cannot be told apart from {@code "❤"} by reading it, and that
 * missing variation selector is exactly what the rule is about.
 */
class SingleEmojiValidatorTest {

    private static final String ZWJ = "\u200D";
    private static final String VARIATION_SELECTOR_16 = "\uFE0F";
    private static final String KEYCAP = "\u20E3";
    private static final String MEDIUM_SKIN_TONE = new String(Character.toChars(0x1F3FD));
    private static final String WOMAN = new String(Character.toChars(0x1F469));

    private static ValidatorFactory factory;
    private static Validator validator;

    /** The constraint as a request record carries it, so the annotation wiring is under test too. */
    private record Holder(@SingleEmoji String emoji) {
    }

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static Set<ConstraintViolation<Holder>> violationsFor(String emoji) {
        return validator.validate(new Holder(emoji));
    }

    private static String codePoints(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    static Stream<String> accepted() {
        return Stream.of(
                "\uD83C\uDF82",                                         // 🎂 birthday cake: emoji by default
                "\uD83C\uDF5D",                                         // 🍝 spaghetti
                "\u2764" + VARIATION_SELECTOR_16,                       // ❤️ text by default, qualified by U+FE0F
                WOMAN + ZWJ + "\uD83C\uDF73",                           // 👩‍🍳 woman cook: a ZWJ sequence
                "\uD83D\uDC4D" + MEDIUM_SKIN_TONE,                      // 👍🏽 a modifier base and a skin tone
                "\uD83C\uDDEB\uD83C\uDDF7",                             // 🇫🇷 two regional indicators
                "1" + VARIATION_SELECTOR_16 + KEYCAP,                   // 1️⃣ keycap
                "#" + VARIATION_SELECTOR_16 + KEYCAP,                   // #️⃣
                "*" + VARIATION_SELECTOR_16 + KEYCAP,                   // *️⃣
                "\uD83C\uDFF3" + VARIATION_SELECTOR_16 + ZWJ + "\uD83C\uDF08",  // 🏳️‍🌈 text-default element inside a ZWJ
                "\uD83C\uDFC3" + MEDIUM_SKIN_TONE + ZWJ + "\u2642" + VARIATION_SELECTOR_16, // 🏃🏽‍♂️ tone, then a qualified sign
                WOMAN + ZWJ + "\uD83D\uDC69" + ZWJ + "\uD83D\uDC67" + ZWJ + "\uD83D\uDC66",  // 👩‍👩‍👧‍👦 a family
                "\u261D" + MEDIUM_SKIN_TONE,                            // ☝🏽 text-default base: the tone qualifies it
                codePoints(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F), // 🏴󠁧󠁢󠁥󠁮󠁧󠁿 England: a tag sequence
                codePoints(0x1F3F4, 0x200D, 0x2620, 0xFE0F),            // 🏴‍☠️ pirate flag: the black flag WITHOUT tags
                "\u00A9" + VARIATION_SELECTOR_16,                       // ©️ a text symbol, qualified
                // 11 woman emojis joined by ZWJ: 11 * 2 + 10 = 32 characters, exactly the column width.
                String.join(ZWJ, java.util.Collections.nCopies(11, WOMAN)));
    }

    static Stream<String> rejected() {
        return Stream.of(
                "",                                                     // nothing
                " ",                                                    // whitespace
                "a",                                                    // a letter
                "1",                                                    // Character.isEmoji is true for a bare digit
                "#",                                                    // ... and for these two
                "*",
                "\u2192",                                               // → an arrow, not an emoji
                "\uD83C\uDF82\uD83C\uDF82",                             // 🎂🎂 two clusters
                "\uD83C\uDF82a",                                        // 🎂a an emoji and a letter
                "a\uD83C\uDF82",
                " \uD83C\uDF82",                                        // padding counts: it is a second cluster
                "\uD83C\uDF82 ",
                ZWJ,                                                    // a lone ZWJ
                MEDIUM_SKIN_TONE,                                       // a lone skin-tone modifier
                MEDIUM_SKIN_TONE + MEDIUM_SKIN_TONE,                    // modifiers with nothing to modify
                "\uD83C\uDF82" + ZWJ,                                   // a ZWJ that joins nothing
                ZWJ + "\uD83C\uDF82",
                "\uD83C\uDF82" + MEDIUM_SKIN_TONE,                      // a skin tone on a cake: not a modifier base
                "\u2764",                                               // ❤ without U+FE0F: the text-style heart
                "\u2764\uFE0E",                                         // ❤︎ explicitly text-style
                "\u00A9",                                               // © a copyright sign is not an emoji until qualified
                VARIATION_SELECTOR_16,                                  // a lone variation selector
                KEYCAP,                                                 // a lone combining keycap
                "1" + KEYCAP,                                           // 1⃣ a keycap not qualified by U+FE0F
                "a" + VARIATION_SELECTOR_16 + KEYCAP,                   // a keycap of a letter
                "\uD83C\uDDEB",                                         // 🇫 one regional indicator
                "\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDE9\uD83C\uDDEA",     // 🇫🇷🇩🇪 two flags are two clusters
                codePoints(0x1F3F4, 0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067), // England without the cancel tag
                codePoints(0x1F382, 0xE0067, 0xE007F),                  // a tag sequence on something that is not a flag
                "e\u0301",                                              // é as e plus an accent: one cluster, not an emoji
                // 12 woman emojis joined by ZWJ: a valid sequence, 35 characters, wider than the column.
                String.join(ZWJ, java.util.Collections.nCopies(12, WOMAN)),
                "\uD83C\uDF82".repeat(17));                             // over 32 characters, and 17 clusters anyway
    }

    @ParameterizedTest(name = "accepts {0}")
    @MethodSource("accepted")
    void acceptsOneEmoji(String emoji) {
        assertThat(violationsFor(emoji)).isEmpty();
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @MethodSource("rejected")
    void rejectsAnythingElse(String text) {
        assertThat(violationsFor(text)).hasSize(1);
    }

    @Test
    void theLengthCapIsNotRedundantWithTheGrammar() {
        // 11 emojis fit exactly; the 12th is still a perfectly well-formed sequence and must be refused for its length alone.
        String atTheLimit = String.join(ZWJ, java.util.Collections.nCopies(11, WOMAN));
        String overTheLimit = String.join(ZWJ, java.util.Collections.nCopies(12, WOMAN));

        assertThat(atTheLimit).hasSize(SingleEmoji.MAX_LENGTH);
        assertThat(violationsFor(atTheLimit)).isEmpty();
        assertThat(overTheLimit).hasSizeGreaterThan(SingleEmoji.MAX_LENGTH);
        assertThat(violationsFor(overTheLimit)).hasSize(1);
    }

    @Test
    void nullIsLeftToNotNull_asEveryFormatConstraintDoes() {
        assertThat(violationsFor(null)).isEmpty();
    }

    @Test
    void theViolationSaysWhatIsExpected() {
        assertThat(violationsFor("a")).singleElement()
                .extracting(ConstraintViolation::getMessage)
                .isEqualTo("must be a single emoji");
    }
}
