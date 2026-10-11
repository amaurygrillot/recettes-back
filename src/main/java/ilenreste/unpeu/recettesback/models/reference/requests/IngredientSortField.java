package ilenreste.unpeu.recettesback.models.reference.requests;

/**
 * What {@code GET /ingredients} may be sorted by.
 * <p>
 * A closed enum and not the free-text {@code sort} that Spring Data's
 * {@code Pageable} binding hands out. That difference is a bug fix, not a style
 * preference, and it is the same one {@code RecipeSortField} exists for — this
 * endpoint was simply missed.
 * <p>
 * {@code @PageableDefault} only supplies a <em>default</em>, so a supplied
 * {@code ?sort=} replaced it and went straight into Spring Data's property
 * resolution. Two things followed on a {@code permitAll()} endpoint:
 * {@code ?sort=bogus} threw {@code PropertyReferenceException}, which nothing
 * maps, so a typo answered <strong>500</strong>; and because paths resolve
 * through associations, {@code ?sort=createdBy.password} was a <em>valid</em>
 * path — {@code IngredientEntity} inherits {@code createdBy} from
 * {@code AuditableEntity} — so an anonymous caller could order the public
 * ingredient list by the bcrypt column, which is an oracle over a value no
 * response exposes.
 * <p>
 * With this enum an unknown value is a 400 naming what is accepted, and the
 * sortable set is decided here rather than by whatever happens to be reachable
 * from the entity.
 */
public enum IngredientSortField {

    /**
     * Alphabetical, and the default: this list feeds an autocomplete.
     * <p>
     * Orders by {@code normalizedName}, not {@code name}, so it agrees with
     * {@code AbstractReferenceService.BY_NAME} and with every other reference
     * listing — case <em>and</em> accents already folded, which is what puts
     * {@code Épices} where a French reader looks for it.
     */
    NAME("normalizedName"),

    /** Most recently added first, for an admin reviewing what users have created. */
    CREATED_AT("createdAt");

    private final String property;

    IngredientSortField(String property) {
        this.property = property;
    }

    /** The JPQL property path on {@code IngredientEntity}, which is what {@code Sort} needs. */
    public String property() {
        return property;
    }
}
