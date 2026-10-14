package ilenreste.unpeu.recettesback.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For an {@code Optional} field: absent is fine, but a value that <em>is</em>
 * supplied must not be empty.
 * <p>
 * <strong>Why this exists rather than {@code Optional<@NotEmpty List<String>>}.</strong>
 * That form looks right and is not. Hibernate Validator's
 * {@code OptionalValueExtractor} is {@code @UnwrapByDefault} and extracts
 * {@code Optional.orElse(null)}, so an <em>absent</em> field arrives at the
 * constraint as {@code null} — which {@code @NotEmpty} and {@code @NotBlank}
 * reject. The result is that every partial update touching none of those fields
 * answers 400, which is precisely the behaviour the {@code Optional} was chosen
 * to avoid.
 * <p>
 * It is easy to miss because {@code @Size} — which the existing
 * {@code UpdateUserRequest} uses — is null-tolerant by specification and works
 * fine in that position. Only the not-null-implying constraints break, and only
 * on the fields nobody sent.
 * <p>
 * Applies to an {@code Optional} of a {@code Collection}, a {@code Map}, a
 * {@code CharSequence} or an array. A supplied {@code CharSequence} must not be
 * blank, not merely non-empty: {@code "   "} is a title nobody meant to set.
 *
 * @see ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest
 */
@Documented
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = NotEmptyIfPresentValidator.class)
public @interface NotEmptyIfPresent {

    String message() default "must not be empty when provided";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
