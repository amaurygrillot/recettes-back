package ilenreste.unpeu.recettesback.entities.recipes;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A recipe.
 * <p>
 * <strong>Does not extend {@code AuditableEntity}, deliberately.</strong> The
 * author and the creating user are the same person, so a separate
 * {@code created_by_id} column would be a second foreign key holding the same
 * value forever — the two drift, and then nobody knows which one drives
 * permissions. Instead {@code author} carries {@code @CreatedBy} directly: set
 * once on insert from the security context, {@code updatable = false}, and it is
 * what the ownership check reads.
 * <p>
 * {@code title} is <strong>not unique</strong>. Two people can legitimately both
 * post "Tarte aux pommes", and one person may keep two variants; a uniqueness
 * constraint here would produce a 409 the user cannot act on. Recipes are
 * addressed by id.
 * <p>
 * {@code recommendations} is a single text field rather than a list, because it
 * is rendered as one block beside the recipe. Markdown in this field covers
 * bullets later without a schema change.
 * <p>
 * <strong>Every ordered collection carries {@code @OrderBy("position")}.</strong>
 * Rejecting {@code @OrderColumn} means nothing re-applies the order on the read
 * path unless it is asked for explicitly, and these are mapped as {@code Set},
 * so Hibernate would otherwise hydrate them into a {@code HashSet} and iterate
 * in hash order. A five-step recipe stored correctly as 0..4 then comes back out
 * of {@code GET /recipes/{id}} shuffled, and no test that round-trips a
 * single-step recipe will ever notice.
 */
@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "recipes", indexes = {
        @Index(name = "idx_recipes_author_id", columnList = "author_id"),
        // No DESC: JPA cannot express it, and PostgreSQL scans a b-tree backwards just as happily,
        // so this serves the default "newest first" listing order.
        @Index(name = "idx_recipes_created_at", columnList = "created_at")
})
public class RecipeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String recommendations;

    /** The owner. Immutable after creation, and what the ownership check reads. */
    @CreatedBy
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private UserEntity author;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by_id")
    private UserEntity updatedBy;

    /**
     * At least one, enforced at the API layer by {@code @NotEmpty} since a join
     * table cannot express a minimum cardinality. No ordering column: unlike
     * steps there is no meaningful "first" category, and the API sorts by name
     * so the output is at least stable.
     * <p>
     * No {@code orphanRemoval}: removing a category from a recipe must delete the
     * join row, never the category.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "recipe_categories",
            joinColumns = @JoinColumn(name = "recipe_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"),
            // The composite primary key indexes (recipe_id, category_id), which covers lookups
            // FROM a recipe. This covers the other direction - the categoryId filter and the
            // in-use check - which a composite PK's index cannot serve.
            indexes = @Index(name = "idx_recipe_categories_category_id", columnList = "category_id")
    )
    private Set<CategoryEntity> categories = new LinkedHashSet<>();

    /** Optional, and clearing them all is a legitimate edit — see {@code UpdateRecipeRequest}. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "recipe_tags",
            joinColumns = @JoinColumn(name = "recipe_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"),
            indexes = @Index(name = "idx_recipe_tags_tag_id", columnList = "tag_id")
    )
    private Set<TagEntity> tags = new LinkedHashSet<>();

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private Set<RecipeStepEntity> steps = new LinkedHashSet<>();

    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private Set<RecipeIngredientGroupEntity> ingredientGroups = new LinkedHashSet<>();

    /**
     * The first of these by position is the recipe's thumbnail in a listing,
     * which is only well-defined because of the {@code @OrderBy} above — without
     * it the picture shown could change between two identical requests with no
     * write in between.
     */
    @OneToMany(mappedBy = "recipe", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position")
    private Set<RecipeCoverPictureEntity> coverPictures = new LinkedHashSet<>();
}
