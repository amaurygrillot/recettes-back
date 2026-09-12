package ilenreste.unpeu.recettesback.entities.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A taxonomy entry: a small, stable set (entrées, plats, desserts) that drives
 * the main navigation. Every recipe must carry at least one, which is what makes
 * every recipe browsable.
 * <p>
 * Unlike {@link TagEntity} — an open-ended, flat facet — categories form a
 * <strong>hierarchy</strong>: "desserts" has "tartes" and "glaces" under it. That
 * is now the structural difference between the two, not just a product
 * convention, so the earlier note about the two eventually merging no longer
 * applies.
 *
 * <h2>How the hierarchy is stored</h2>
 * The parent link is <strong>not</strong> a column here. It lives in its own
 * {@code categories_relation} table, mapped by {@link CategoryRelationEntity},
 * with exactly one row per category:
 * <ul>
 *   <li>a {@link CategoryType#SUB} category's row points at its <em>directly</em>
 *       superior category;</li>
 *   <li>a {@link CategoryType#MAIN} category's row points at <em>itself</em>.</li>
 * </ul>
 * The self-pointing root is unusual and deliberate: with it, "every category has
 * exactly one relation row" is a total invariant the database enforces with
 * {@code NOT NULL}, and a category with no row is unambiguously a bug rather than
 * a root. A nullable parent column would make those two states look identical.
 * <p>
 * Only the <em>direct</em> parent is stored. Ancestors and descendants are walked
 * from those edges rather than materialised (no closure table, no path column):
 * categories are a small closed set read on every page and written a handful of
 * times a year, so one query for the whole edge list beats keeping a denormalised
 * transitive closure honest. If this table ever grows past a few hundred rows,
 * revisit with a PostgreSQL recursive CTE.
 *
 * @see CategoryType
 */
@Getter
@Setter
@Entity
@Table(name = "categories", indexes =
@Index(name = "idx_categories_category_type", columnList = "category_type"))
public class CategoryEntity extends ReferenceEntity {

    /**
     * Mirrors the shape of this category's {@code categories_relation} row so the
     * roots can be found with an indexed predicate. Written only by
     * {@code CategoryService}; see {@link CategoryType} for why it is stored at
     * all.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category_type", nullable = false, length = 4)
    private CategoryType categoryType = CategoryType.MAIN;
}
