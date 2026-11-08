package ilenreste.unpeu.recettesback.exceptions;

/**
 * A recipe list rule refused the request — mapped to {@code 409 Conflict} with the {@code type} and
 * {@code title} of its {@link RecipeListConflict}.
 * <p>
 * A {@link ResourceConflictException}, so anything that handles conflicts in general still sees it as one;
 * {@code ApiExceptionHandler} has a more specific handler that adds what tells the three apart.
 */
public class RecipeListConflictException extends ResourceConflictException {

    private final RecipeListConflict conflict;

    public RecipeListConflictException(RecipeListConflict conflict, String message) {
        super(message);
        this.conflict = conflict;
    }

    public RecipeListConflict conflict() {
        return conflict;
    }
}
