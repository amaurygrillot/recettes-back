package ilenreste.unpeu.recettesback.models.recipes.requests;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the one thing the design document explicitly left unverified: what
 * {@code Optional<@NotEmpty List<String>>} actually does, on this classpath, in both states.
 * <p>
 * {@code Optional} answers "was this field supplied?" and {@code @NotEmpty} answers "is the
 * supplied value usable?". Those are different questions, and {@code categoryIds} is the field
 * where both need answering - so the constraint has to sit on the <em>contained list</em>, not on
 * the {@code Optional} wrapping it.
 * <p>
 * The answer, found by writing this test: {@code Optional<@NotEmpty List<String>>} fails
 * <strong>quietly</strong> in the worst direction. {@code OptionalValueExtractor} is
 * {@code @UnwrapByDefault} and extracts {@code Optional.orElse(null)}, so an absent field arrives
 * as {@code null} and {@code @NotEmpty} rejects it - every partial update that does not mention
 * categories would answer 400. {@code @NotBlank} on {@code title} broke identically.
 * {@code UpdateUserRequest} is not a counterexample: {@code @Size} is null-tolerant by
 * specification, so it passes the absent case by accident.
 * <p>
 * Hence {@code @NotEmptyIfPresent}, which says what is actually meant. This test is what makes
 * that non-obvious choice defensible, and what stops someone "simplifying" it back.
 */
class UpdateRecipeRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void stopValidator() {
        factory.close();
    }

    /** Every field absent: the shape a partial update that touches nothing takes. */
    private static UpdateRecipeRequest nothing() {
        return new UpdateRecipeRequest(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private UpdateRecipeRequest withCategoryIds(Optional<List<String>> categoryIds) {
        return new UpdateRecipeRequest(Optional.empty(), Optional.empty(), Optional.empty(),
                categoryIds, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private UpdateRecipeRequest withIngredientGroups(
            Optional<List<RecipeIngredientGroupRequest>> ingredientGroups) {
        return new UpdateRecipeRequest(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), ingredientGroups,
                Optional.empty());
    }

    private UpdateRecipeRequest withSteps(Optional<List<RecipeStepRequest>> steps) {
        return new UpdateRecipeRequest(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), steps);
    }

    @Test
    void anOmittedCategoryIdsIsValid_becauseAbsentMeansDoNotTouchThem() {
        Set<ConstraintViolation<UpdateRecipeRequest>> violations =
                validator.validate(withCategoryIds(Optional.empty()));

        assertThat(violations).isEmpty();
    }

    @Test
    void anEmptyCategoryIdsIsRejected_becauseARecipeMayNotLoseItsLastCategory() {
        // CreateRecipeRequest enforces "at least one category" at creation; an update must not be
        // a back door around it, or the invariant that every recipe is browsable quietly dies.
        Set<ConstraintViolation<UpdateRecipeRequest>> violations =
                validator.validate(withCategoryIds(Optional.of(List.of())));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath()).hasToString("categoryIds");
    }

    @Test
    void aPopulatedCategoryIdsIsValid() {
        assertThat(validator.validate(withCategoryIds(Optional.of(List.of("cat-1"))))).isEmpty();
    }

    @Test
    void tagIdsHasTheSameShapeButNoSuchRule_becauseClearingEveryTagIsALegitimateEdit() {
        // Same Optional<List<String>> type, deliberately different rule. The annotation is the only
        // thing expressing the difference, which is why both halves are asserted.
        UpdateRecipeRequest clearingTags = new UpdateRecipeRequest(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(List.of()), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertThat(validator.validate(clearingTags)).isEmpty();
    }

    @Test
    void aBlankTitleIsRejected_butAnOmittedOneIsNot() {
        UpdateRecipeRequest blank = new UpdateRecipeRequest(Optional.of("   "), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertThat(validator.validate(blank)).hasSize(1);
        assertThat(validator.validate(withCategoryIds(Optional.empty()))).isEmpty();
    }

    /**
     * Cascading has to reach through two containers - Optional, then List - to the element. If it
     * does not, a step with a null instruction sails past validation and dies on
     * recipe_steps.instruction NOT NULL instead, which surfaces as a generic 500.
     */
    @Test
    void validationCascadesIntoNestedStepRecords() {
        UpdateRecipeRequest blankInstruction =
                withSteps(Optional.of(List.of(new RecipeStepRequest("  ", List.of()))));

        Set<ConstraintViolation<UpdateRecipeRequest>> violations = validator.validate(blankInstruction);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath())
                .hasToString("steps[0].instruction");
    }

    @Test
    void validationCascadesIntoIngredientLines() {
        UpdateRecipeRequest badLine = withIngredientGroups(
                Optional.of(List.of(new RecipeIngredientGroupRequest(null,
                        List.of(new RecipeIngredientRequest("", null, null, null))))));

        assertThat(validator.validate(badLine)).hasSize(1);
    }

    @Test
    void anEmptyIngredientGroupIsRejected_becauseAHeadingWithNothingUnderItIsNeverIntended() {
        UpdateRecipeRequest emptyGroup = withIngredientGroups(
                Optional.of(List.of(new RecipeIngredientGroupRequest("Pour la pate", List.of()))));

        assertThat(validator.validate(emptyGroup)).hasSize(1);
    }

    /**
     * The same rule as categoryIds, for the same reason: an edit must not be able to turn a
     * working recipe into one nobody can cook, which a create is already forbidden from producing.
     * Absent still means "leave them alone".
     */
    @Test
    void anEmptyIngredientGroupsOrStepsListIsRejected_butAnOmittedOneIsNot() {
        assertThat(validator.validate(withIngredientGroups(Optional.of(List.of())))).hasSize(1);
        assertThat(validator.validate(withSteps(Optional.of(List.of())))).hasSize(1);

        assertThat(validator.validate(withIngredientGroups(Optional.empty()))).isEmpty();
        assertThat(validator.validate(withSteps(Optional.empty()))).isEmpty();
    }

    /**
     * The introduction is the one field where the empty case is the useful one. Jackson maps both
     * a missing property and an explicit null to Optional.empty(), so "delete this section" has to
     * be spelled as the object with blank text - which means no constraint may reject it.
     */
    @Test
    void aBlankIntroductionIsValid_becauseThatIsHowTheSectionIsRemoved() {
        UpdateRecipeRequest removing = new UpdateRecipeRequest(Optional.empty(),
                Optional.of(new RecipeIntroductionRequest("")), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(validator.validate(removing)).isEmpty();
        assertThat(validator.validate(nothing())).isEmpty();
    }
}
