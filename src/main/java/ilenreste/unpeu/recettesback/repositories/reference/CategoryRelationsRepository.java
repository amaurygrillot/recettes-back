package ilenreste.unpeu.recettesback.repositories.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryRelationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The category tree's edges.
 * <p>
 * There is a {@link #findChildrenOf(String)} for one level, and deliberately no
 * {@code findDescendantsOf}. A whole-tree read loads the edge list once with
 * {@link #findAllEdges()} and walks it in memory, because the alternative —
 * one query per level — is an N+1 whose depth is chosen by the data. Categories
 * are a closed set of tens of rows read on every navigation render, so a single
 * two-column scan is both cheaper and simpler than a recursive query. See
 * {@code CategoryEntity} for the size at which that stops being true.
 */
public interface CategoryRelationsRepository extends JpaRepository<CategoryRelationEntity, String> {

    /**
     * Every edge as a {@code (categoryId, parentCategoryId)} pair, without
     * hydrating a single {@code CategoryEntity}.
     * <p>
     * Returning the entities instead would make {@code getParentCategory()}
     * either a lazy proxy per row or a fetch join pulling the full category twice
     * over, to answer a question that only ever needs two ids.
     */
    @Query("SELECT r.categoryId, r.parentCategory.id FROM CategoryRelationEntity r")
    List<Object[]> findAllEdges();

    /**
     * The edge list as {@code categoryId -> parentCategoryId}, with the roots'
     * self-references <strong>dropped</strong> rather than carried through.
     * <p>
     * This is the one place that translation happens. Inside the schema a root
     * points at itself, which is what keeps {@code parent_category_id}
     * {@code NOT NULL} and makes "no relation row" unambiguously a bug; outside
     * it, a root simply has no parent. Letting the self-reference escape would
     * put an {@code id.equals(parentId)} check in every caller, and the first
     * one to forget it would render a category as its own parent — or loop.
     */
    default Map<String, String> parentIdsByCategoryId() {
        Map<String, String> parents = new LinkedHashMap<>();
        for (Object[] edge : findAllEdges()) {
            String categoryId = (String) edge[0];
            String parentId = (String) edge[1];
            if (!categoryId.equals(parentId)) {
                parents.put(categoryId, parentId);
            }
        }
        return parents;
    }

    /**
     * The direct children of one category — a single level, not the subtree —
     * ordered by name like every other reference listing.
     * <p>
     * The {@code <>} is load-bearing for the same reason as in
     * {@link #hasSubcategories(String)}: a main category is its own parent, so
     * without it a root would be returned as its own child. A category with no
     * subcategories, and an id that matches nothing, both give an empty list.
     */
    @Query("""
            SELECT r.category FROM CategoryRelationEntity r
            WHERE r.parentCategory.id = :parentCategoryId AND r.categoryId <> :parentCategoryId
            ORDER BY r.category.normalizedName
            """)
    List<CategoryEntity> findChildrenOf(String parentCategoryId);

    /**
     * Whether anything hangs off this category — the guard that stops a delete
     * orphaning a subtree.
     * <p>
     * The {@code <>} is load-bearing: a main category is its own parent, so
     * without it every root would report having children and could never be
     * deleted.
     */
    @Query("""
            SELECT COUNT(r) > 0 FROM CategoryRelationEntity r
            WHERE r.parentCategory.id = :categoryId AND r.categoryId <> :categoryId
            """)
    boolean hasSubcategories(String categoryId);
}
