package ilenreste.unpeu.recettesback.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value is exactly one emoji: <strong>one extended grapheme cluster, and that cluster is a
 * fully-qualified emoji sequence</strong>, at most {@value #MAX_LENGTH} characters.
 * <p>
 * The grammar, and why it is not simply {@link Character#isEmoji(int)}, are written out in
 * docs/recipe-lists.md and implemented by {@link SingleEmojiValidator}. In short: that property is
 * true of the bare digits, {@code #} and {@code *}, so on its own it accepts {@code "1"}; and a lone
 * ZWJ or skin-tone swatch passes any test that only looks at how a cluster starts.
 * <p>
 * {@code null} is valid, as for every format constraint ({@code @Size}, {@code @Pattern}): pair it
 * with {@code @NotNull} to require a value.
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = SingleEmojiValidator.class)
public @interface SingleEmoji {

    /**
     * The width of the {@code varchar} column that stores it, in characters. A ZWJ sequence has no maximum of
     * its own, so this is a rule the grammar does not imply. Java's {@code length()} counts UTF-16 units,
     * never fewer than the code points PostgreSQL counts, so whatever passes here fits.
     */
    int MAX_LENGTH = 32;

    String message() default "must be a single emoji";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
