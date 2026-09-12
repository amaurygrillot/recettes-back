package ilenreste.unpeu.recettesback.entities.reference;

/**
 * Where a category sits in the taxonomy.
 * <p>
 * Strictly speaking this is derivable — a category is {@link #MAIN} exactly when
 * its {@code categories_relation} row points at itself — and storing a derived
 * value is normally a mistake. It is stored anyway, for two reasons that are
 * specific to this shape:
 * <ul>
 *   <li>Finding the roots is the first query of every navigation render, and
 *       {@code WHERE category_type = 'MAIN'} is an indexable predicate where
 *       {@code WHERE category_id = superior_category_id} is a self-comparison no
 *       index can serve.</li>
 *   <li>It makes the invariant readable from plain SQL while debugging, instead
 *       of something you have to reconstruct by joining a table to itself.</li>
 * </ul>
 * The duplication is safe only because <strong>one</strong> method writes both —
 * {@code CategoryService.linkTo} — and a test asserts they agree. Do not set this
 * column anywhere else.
 */
public enum CategoryType {

    /** A root of the taxonomy. Its relation row points at itself, per the schema contract. */
    MAIN,

    /** Sits under exactly one other category, named by its relation row. */
    SUB
}
