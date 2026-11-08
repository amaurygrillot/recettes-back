package ilenreste.unpeu.recettesback.exceptions;

/**
 * A recipe list rule refused the request — mapped to {@code 409 Conflict} with the {@code type} and
 * {@code title} of its {@link RecipeListConflict}.
 * <p>
 * A {@link ResourceConflictException}, so anything that handles conflicts in general still sees it as one;
 * {@code ApiExceptionHandler} has a more specific handler that adds what tells the three apart.
 * <p>
 * Built through the factories below so that the wording of each conflict lives next to its type — which
 * matters for {@link #nameTaken()}, raised by the service's own check <em>and</em> by the handler when the
 * database constraint wins a race, and which must read the same either way.
 */
public class RecipeListConflictException extends ResourceConflictException {

    private final RecipeListConflict conflict;

    private RecipeListConflictException(RecipeListConflict conflict, String message) {
        super(message);
        this.conflict = conflict;
    }

    public static RecipeListConflictException nameTaken() {
        return new RecipeListConflictException(RecipeListConflict.NAME_TAKEN,
                "You already have a list with that name.");
    }

    /** @param max the limit, which the service owns and passes in */
    public static RecipeListConflictException tooManyLists(int max) {
        return new RecipeListConflictException(RecipeListConflict.TOO_MANY_LISTS,
                "You already have %d lists, which is the maximum.".formatted(max));
    }

    /** @param max the limit, which the service owns and passes in */
    public static RecipeListConflictException listFull(int max) {
        return new RecipeListConflictException(RecipeListConflict.LIST_FULL,
                "This list already holds %d recipes, which is the maximum.".formatted(max));
    }

    public RecipeListConflict conflict() {
        return conflict;
    }
}
