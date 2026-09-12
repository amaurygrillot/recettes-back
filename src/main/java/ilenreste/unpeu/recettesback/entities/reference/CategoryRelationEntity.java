package ilenreste.unpeu.recettesback.entities.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One edge of the category tree: this category, and the category directly above
 * it.
 * <p>
 * {@code category_id} is the <strong>primary key</strong>, not just a column.
 * That is the whole point of the table: a primary key is a uniqueness constraint,
 * so the database itself guarantees a category cannot have two parents. Modelled
 * as a nullable {@code superior_category_id} column on {@code categories} the
 * same guarantee would be free, but a separate table is what was specified, and
 * it buys one real thing — the edge can be deleted and rewritten without touching
 * the category row an admin is concurrently renaming.
 * <p>
 * A {@link CategoryType#MAIN} category's row points at <strong>itself</strong>
 * ({@code superior_category_id = category_id}). See {@link CategoryEntity} for
 * why the roots are spelled that way rather than as a missing row.
 * <p>
 * {@code superior_category_id} carries its own index: PostgreSQL indexes primary
 * keys and unique constraints for free but never foreign keys, and "give me the
 * children of X" — the query behind every hierarchy read and the guard on every
 * delete — searches exactly that column.
 * <p>
 * Deliberately <strong>not</strong> audited. A relation row is rewritten whenever
 * a category is re-parented, and who moved a node is already recorded on the
 * category itself, which is the row a human looks at.
 */
@Getter
@Setter
@Entity
@Table(name = "categories_relation", indexes =
@Index(name = "idx_categories_relation_superior", columnList = "superior_category_id"))
public class CategoryRelationEntity {

    /**
     * Shares its value with {@link #category} via {@link MapsId}, so there is one
     * id rather than a surrogate key plus a unique constraint that says the same
     * thing.
     */
    @Id
    private String categoryId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    /** The <em>directly</em> superior category, or this same category when it is a root. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "superior_category_id", nullable = false)
    private CategoryEntity superiorCategory;
}
