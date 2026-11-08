package ilenreste.unpeu.recettesback.exceptions;

import java.net.URI;

/**
 * The three ways a recipe list can answer {@code 409}, which a client has to tell apart <strong>without
 * parsing a message</strong>: « ce nom est déjà pris » beside the name field, « tu as atteint 50 listes » as a
 * banner, « cette liste est pleine » on the sheet.
 * <p>
 * They share the status and differ in the RFC 9457 {@code type} and {@code title}. The {@code type} is a
 * {@code urn:}, not a URL: it names the failure, and nothing serves a page at it. See
 * docs/recipe-lists.md and docs/api-error-handling.md.
 */
public enum RecipeListConflict {

    /** Another list of the same member has this name, once accents, case and spacing are ignored. */
    NAME_TAKEN("recipe-list-name-taken", "Recipe list name already used"),

    /** The member already has the maximum number of lists. */
    TOO_MANY_LISTS("too-many-recipe-lists", "Too many recipe lists"),

    /** The list already holds the maximum number of recipes, and the one asked for is not among them. */
    LIST_FULL("recipe-list-full", "Recipe list is full");

    private static final String TYPE_PREFIX = "urn:recettes:problem:";

    private final URI type;
    private final String title;

    RecipeListConflict(String slug, String title) {
        this.type = URI.create(TYPE_PREFIX + slug);
        this.title = title;
    }

    /** The problem {@code type} a client switches on. */
    public URI type() {
        return type;
    }

    /** The problem {@code title}: a human label, distinct per conflict, for the same reason as the type. */
    public String title() {
        return title;
    }
}
