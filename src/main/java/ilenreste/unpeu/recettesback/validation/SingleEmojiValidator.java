package ilenreste.unpeu.recettesback.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.text.BreakIterator;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * Implements {@link SingleEmoji}.
 * <p>
 * Two questions, asked in order: is the text <em>one</em> grapheme cluster, and is that cluster an
 * emoji sequence? The first is {@link BreakIterator}'s (UAX #29 extended grapheme clusters, which is
 * what keeps {@code 👩‍🍳}, {@code 👍🏽}, {@code 🇫🇷} and {@code 1️⃣} whole). The second is this grammar
 * over the code points, written with the {@link Character} emoji properties (Java 21):
 *
 * <pre>
 * emoji        = keycap | flag | tag-sequence | zwj-sequence
 * keycap       = [0-9#*] U+FE0F U+20E3
 * flag         = RI RI                              RI = regional indicator
 * tag-sequence = U+1F3F4 tag+ U+E007F               tag = U+E0020..U+E007E
 * zwj-sequence = element ( U+200D element )*        one element is a plain emoji
 * element      = pictograph U+FE0F?  |  modifier-base modifier
 * pictograph   = Character.isEmoji, except [0-9#*], emoji modifiers and regional indicators
 * </pre>
 *
 * U+FE0F after a pictograph is required unless the pictograph is an emoji by default
 * ({@link Character#isEmojiPresentation(int)}): {@code ❤} alone is a text-style heart, {@code ❤️} is
 * the emoji. Deliberately not checked: that a flag's two letters are a real country, and that the
 * sequence is one Unicode recommends for keyboards. See docs/recipe-lists.md.
 */
public class SingleEmojiValidator implements ConstraintValidator<SingleEmoji, CharSequence> {

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int VARIATION_SELECTOR_16 = 0xFE0F;
    private static final int COMBINING_KEYCAP = 0x20E3;
    private static final int WAVING_BLACK_FLAG = 0x1F3F4;
    private static final int FIRST_TAG = 0xE0020;
    private static final int LAST_TAG = 0xE007E;
    private static final int CANCEL_TAG = 0xE007F;
    private static final int FIRST_REGIONAL_INDICATOR = 0x1F1E6;
    private static final int LAST_REGIONAL_INDICATOR = 0x1F1FF;

    /** Null is valid: this is a format constraint, and presence is {@code @NotNull}'s business. */
    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        String text = value.toString();
        return text.length() <= SingleEmoji.MAX_LENGTH
                && isOneGraphemeCluster(text)
                && isEmojiSequence(text.codePoints().toArray());
    }

    /** A new iterator per call: {@link BreakIterator} is not thread-safe and this validator is shared. */
    private static boolean isOneGraphemeCluster(String text) {
        BreakIterator boundaries = BreakIterator.getCharacterInstance(Locale.ROOT);
        boundaries.setText(text);
        // The first boundary after the start is the end of the text, or there is more than one cluster;
        // for the empty text, next() is DONE (-1), which is not its length (0).
        return boundaries.next() == text.length();
    }

    private static boolean isEmojiSequence(int[] codePoints) {
        return isKeycap(codePoints) || isFlag(codePoints) || isTagSequence(codePoints) || isZwjSequence(codePoints);
    }

    private static boolean isKeycap(int[] codePoints) {
        return codePoints.length == 3
                && isKeycapBase(codePoints[0])
                && codePoints[1] == VARIATION_SELECTOR_16
                && codePoints[2] == COMBINING_KEYCAP;
    }

    private static boolean isFlag(int[] codePoints) {
        return codePoints.length == 2 && isRegionalIndicator(codePoints[0]) && isRegionalIndicator(codePoints[1]);
    }

    private static boolean isTagSequence(int[] codePoints) {
        int last = codePoints.length - 1;
        return codePoints.length >= 3
                && codePoints[0] == WAVING_BLACK_FLAG
                && codePoints[last] == CANCEL_TAG
                && IntStream.range(1, last).map(index -> codePoints[index])
                        .allMatch(codePoint -> codePoint >= FIRST_TAG && codePoint <= LAST_TAG);
    }

    private static boolean isZwjSequence(int[] codePoints) {
        int next = endOfElement(codePoints, 0);
        while (next != NO_ELEMENT && next < codePoints.length) {
            if (codePoints[next] != ZERO_WIDTH_JOINER) {
                return false;
            }
            next = endOfElement(codePoints, next + 1);
        }
        return next == codePoints.length;
    }

    private static final int NO_ELEMENT = -1;

    /** The index just past the element that starts at {@code start}, or {@link #NO_ELEMENT} if none does. */
    private static int endOfElement(int[] codePoints, int start) {
        if (start >= codePoints.length) {
            return NO_ELEMENT;
        }
        int first = codePoints[start];
        int after = start + 1;
        boolean followedByModifier = after < codePoints.length && Character.isEmojiModifier(codePoints[after]);
        if (Character.isEmojiModifierBase(first) && followedByModifier) {
            return after + 1;
        }
        if (!isPictograph(first)) {
            return NO_ELEMENT;
        }
        if (after < codePoints.length && codePoints[after] == VARIATION_SELECTOR_16) {
            return after + 1;
        }
        return Character.isEmojiPresentation(first) ? after : NO_ELEMENT;
    }

    /**
     * An emoji in its own right. {@link Character#isEmoji(int)} alone is too wide: the property is also true of
     * the digits, {@code #} and {@code *} (which are emoji only inside a keycap), of the skin-tone modifiers
     * (which are emoji only after a modifier base) and of the regional indicators (only in pairs).
     */
    private static boolean isPictograph(int codePoint) {
        return Character.isEmoji(codePoint)
                && !isKeycapBase(codePoint)
                && !Character.isEmojiModifier(codePoint)
                && !isRegionalIndicator(codePoint);
    }

    private static boolean isKeycapBase(int codePoint) {
        return (codePoint >= '0' && codePoint <= '9') || codePoint == '#' || codePoint == '*';
    }

    private static boolean isRegionalIndicator(int codePoint) {
        return codePoint >= FIRST_REGIONAL_INDICATOR && codePoint <= LAST_REGIONAL_INDICATOR;
    }
}
