package ilenreste.unpeu.recettesback.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Implements {@link NotEmptyIfPresent}.
 * <p>
 * The whole point is the first branch: an absent {@code Optional} is
 * <strong>valid</strong>. Everything after it only runs for a value the caller
 * actually sent.
 */
public class NotEmptyIfPresentValidator implements ConstraintValidator<NotEmptyIfPresent, Optional<?>> {

    @Override
    public boolean isValid(Optional<?> value, ConstraintValidatorContext context) {
        // A null field and an absent Optional both mean "not supplied". Nothing to check.
        if (value == null || value.isEmpty()) {
            return true;
        }
        return switch (value.get()) {
            case Collection<?> collection -> !collection.isEmpty();
            case Map<?, ?> map -> !map.isEmpty();
            // Blank, not merely empty: "   " is a title nobody meant to set.
            case CharSequence text -> !text.toString().isBlank();
            case Object other when other.getClass().isArray() -> Array.getLength(other) > 0;
            default -> true;
        };
    }
}
