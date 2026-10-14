package ilenreste.unpeu.recettesback.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class NotEmptyIfPresentValidatorTest {

    private final NotEmptyIfPresentValidator validator = new NotEmptyIfPresentValidator();

    private boolean isValid(Optional<?> value) {
        return validator.isValid(value, null);
    }

    @Test
    void anAbsentValueIsValid_whichIsTheWholePoint() {
        // The failure this constraint exists to prevent: with @NotEmpty here, every partial update
        // that does not mention the field would be a 400.
        assertThat(isValid(Optional.empty())).isTrue();
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void rejectsSuppliedButEmptyContainers() {
        assertThat(isValid(Optional.of(List.of()))).isFalse();
        assertThat(isValid(Optional.of(Map.of()))).isFalse();
        assertThat(isValid(Optional.of(new String[0]))).isFalse();
    }

    @Test
    void acceptsSuppliedAndPopulatedContainers() {
        assertThat(isValid(Optional.of(List.of("a")))).isTrue();
        assertThat(isValid(Optional.of(Map.of("a", "b")))).isTrue();
        assertThat(isValid(Optional.of(new String[]{"a"}))).isTrue();
    }

    @Test
    void treatsAWhitespaceOnlyStringAsEmpty() {
        // Blank, not merely empty: "   " is a title nobody meant to set.
        assertThat(isValid(Optional.of("   "))).isFalse();
        assertThat(isValid(Optional.of(""))).isFalse();
        assertThat(isValid(Optional.of("Tarte"))).isTrue();
    }

    @Test
    void leavesTypesItHasNoOpinionAboutAlone() {
        // Emptiness is meaningless for a number, so a supplied one is simply valid.
        assertThat(isValid(Optional.of(42))).isTrue();
    }
}
