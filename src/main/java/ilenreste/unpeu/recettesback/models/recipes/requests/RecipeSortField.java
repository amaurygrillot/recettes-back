package ilenreste.unpeu.recettesback.models.recipes.requests;

/**
 * What {@code GET /recipes} may be sorted by.
 * <p>
 * A closed enum and not a free-text {@code sort} parameter, which is what Spring
 * Data's {@code Pageable} binding would otherwise give us. That difference is a
 * bug fix, not a style preference: the listing pages over a hand-written JPQL
 * projection, and Spring appends the requested property to it verbatim. Ask for
 * {@code ?sort=bogus} and Hibernate fails to resolve the path and throws a
 * {@code SemanticException} — a <strong>500</strong> caused entirely by a query
 * string, on a public endpoint, from a typo. Ask for {@code ?sort=author.password}
 * and the ordering leaks information about a column no response exposes.
 * <p>
 * With this enum an unknown value is a 400 naming what is accepted, and the set
 * of sortable columns is decided here rather than by whatever happens to be a
 * field on the entity.
 * <p>
 * Adding an option is one constant plus its JPQL path — the direction is
 * independent and already free, so "sorted by title, ascending" needs nothing new.
 */
public enum RecipeSortField {

    /** Newest first by default. The listing's natural order, and indexed. */
    CREATED_AT("createdAt"),

    /** Recently edited first — "what changed since I last looked". */
    UPDATED_AT("updatedAt"),

    /**
     * Alphabetical. Case-sensitive, because PostgreSQL sorts by the database
     * collation and forcing {@code LOWER(title)} here would throw away any index
     * a future deployment adds for exactly this order.
     */
    TITLE("title");

    private final String property;

    RecipeSortField(String property) {
        this.property = property;
    }

    /** The JPQL property path on {@code RecipeEntity}, which is what {@code Sort} needs. */
    public String property() {
        return property;
    }
}
