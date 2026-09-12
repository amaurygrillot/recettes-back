package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every category and every parent edge, read once and then walked in memory.
 * <p>
 * This exists so that a hierarchy question costs <strong>two queries whatever the
 * depth of the tree</strong>. Ask the repositories per level instead and the
 * query count is chosen by the data, which is the definition of an N+1; ask them
 * per call site and {@code GET /categories} alone runs the same two queries four
 * times over. One snapshot, passed down, answers all of it.
 * <p>
 * A snapshot, not a cache: it is built inside one transaction and thrown away at
 * the end of it, so it can never serve a stale tree to a later request. Nothing
 * here writes.
 * <p>
 * Every walk carries a visited set. That is not defensive programming for its own
 * sake — {@code CategoryService} refuses to write a cycle, but a row edited by
 * hand in psql would otherwise turn a {@code GET} into a non-terminating
 * recursion, and a hang is a far worse failure than a wrong answer.
 */
final class CategoryTaxonomy {

    private static final Comparator<CategoryEntity> BY_NAME =
            Comparator.comparing(CategoryEntity::getName, String.CASE_INSENSITIVE_ORDER);

    private final Map<String, CategoryEntity> byId = new LinkedHashMap<>();
    /** {@code categoryId -> superiorCategoryId}, roots absent. */
    private final Map<String, String> superiors;
    /** {@code superiorCategoryId -> direct children}, name-sorted. */
    private final Map<String, List<CategoryEntity>> children = new LinkedHashMap<>();

    /**
     * @param superiors the map from
     *                  {@code CategoryRelationsRepository.superiorIdsByCategoryId()},
     *                  in which a root has <strong>no entry</strong> rather than an
     *                  entry pointing at itself. That normalisation happens once, at
     *                  the repository, so nothing here has to remember it — and a
     *                  root cannot end up listed among its own children, which would
     *                  make every walk below immediately cyclic
     */
    CategoryTaxonomy(List<CategoryEntity> categories, Map<String, String> superiors) {
        this.superiors = superiors;
        for (CategoryEntity category : categories.stream().sorted(BY_NAME).toList()) {
            byId.put(category.getId(), category);
            String superiorId = superiors.get(category.getId());
            if (superiorId != null) {
                children.computeIfAbsent(superiorId, key -> new ArrayList<>()).add(category);
            }
        }
    }

    /** The parent's id, or null for a root. */
    String superiorIdOf(String categoryId) {
        return superiors.get(categoryId);
    }

    List<CategoryEntity> roots() {
        return byId.values().stream()
                .filter(category -> category.getCategoryType() == CategoryType.MAIN)
                .toList();
    }

    List<CategoryEntity> childrenOf(String categoryId) {
        return children.getOrDefault(categoryId, List.of());
    }

    /** Everything below this category at any depth, parents before their own children. */
    List<CategoryEntity> descendantsOf(String categoryId) {
        List<CategoryEntity> descendants = new ArrayList<>();
        collectDescendants(categoryId, new LinkedHashSet<>(Set.of(categoryId)), descendants);
        return List.copyOf(descendants);
    }

    /** The ancestor chain, nearest first. Empty for a root. */
    List<CategoryEntity> ancestorsOf(String categoryId) {
        List<CategoryEntity> ancestors = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(Set.of(categoryId));
        for (String current = superiorIdOf(categoryId);
             current != null && seen.add(current);
             current = superiorIdOf(current)) {
            CategoryEntity ancestor = byId.get(current);
            if (ancestor == null) {
                break;
            }
            ancestors.add(ancestor);
        }
        return List.copyOf(ancestors);
    }

    private void collectDescendants(String categoryId, Set<String> seen, List<CategoryEntity> into) {
        for (CategoryEntity child : childrenOf(categoryId)) {
            if (!seen.add(child.getId())) {
                continue;
            }
            into.add(child);
            collectDescendants(child.getId(), seen, into);
        }
    }
}
