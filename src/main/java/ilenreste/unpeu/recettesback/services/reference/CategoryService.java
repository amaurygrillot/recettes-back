package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryRelationEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryType;
import ilenreste.unpeu.recettesback.exceptions.InvalidInputException;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.exceptions.ResourceConflictException;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.CategoryRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryTreeResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoryRelationsRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Categories, and the tree they form.
 * <p>
 * Everything public here returns a <strong>response record, never an entity</strong>.
 * A controller handed a {@code CategoryEntity} can reach the whole object graph
 * through it, will eventually serialise something meant to stay on the server,
 * and — with {@code open-in-view} off — can just as easily touch a lazy
 * association after the transaction has closed. Mapping happens here, inside the
 * transaction that loaded the row.
 *
 * <h2>The tree</h2>
 * Parent edges live in {@code categories_relation}, one row per category, a main
 * category pointing at itself. Every read builds a {@link CategoryTaxonomy} — the
 * whole category list plus the whole edge list, two queries — and walks that in
 * memory rather than querying per level; see that class for why.
 * <p>
 * Two invariants are enforced on every write, and both matter more than they
 * look:
 * <ul>
 *   <li><strong>No cycles.</strong> Filing a category under one of its own
 *       descendants would make every walk below it non-terminating — a hang, not
 *       an error — and would detach the whole subtree from any root.</li>
 *   <li><strong>{@code categoryType} agrees with the relation row.</strong> Both
 *       are written by {@link #linkTo} and by nothing else, which is the only
 *       reason storing a derived column is safe here.</li>
 * </ul>
 */
@Log4j2
@Service
public class CategoryService extends AbstractReferenceService<CategoryEntity> {

    private final CategoriesRepository categoriesRepository;
    private final CategoryRelationsRepository relationsRepository;
    private final RecipesRepository recipesRepository;
    private final ReferenceMapper mapper;

    public CategoryService(CategoriesRepository categoriesRepository,
                           CategoryRelationsRepository relationsRepository,
                           RecipesRepository recipesRepository, ReferenceNameNormalizer normalizer,
                           ReferenceMapper mapper) {
        super(categoriesRepository, normalizer);
        this.categoriesRepository = categoriesRepository;
        this.relationsRepository = relationsRepository;
        this.recipesRepository = recipesRepository;
        this.mapper = mapper;
    }

    @Override
    protected CategoryEntity newEntity() {
        return new CategoryEntity();
    }

    @Override
    protected String resourceName() {
        return "category";
    }

    @Override
    protected boolean isUsed(String id) {
        return recipesRepository.isCategoryUsed(id);
    }

    /**
     * Refuses a category that still has something under it, and otherwise clears
     * the edge that would outlive the row.
     * <p>
     * The refusal is not cosmetic. Deleting a node its children still point at
     * would fail on the foreign key as a raw 500 — or, had that constraint been
     * missing, leave a subtree naming a row that no longer exists: unreachable
     * from any root and invisible to every hierarchy query. Re-parenting or
     * deleting the children is a decision only an admin can make.
     * <p>
     * Its own relation row has to go explicitly: it is the one reference to this
     * category that deleting the category cannot resolve, because for a main
     * category it points at itself.
     */
    @Override
    protected void beforeDelete(CategoryEntity category) {
        if (relationsRepository.hasSubcategories(category.getId())) {
            log.info("Refusing to delete category {}: it still has subcategories", category.getId());
            throw new ResourceConflictException(
                    "This category still has subcategories. Move or delete them first.");
        }
        relationsRepository.deleteById(category.getId());
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> list() {
        CategoryTaxonomy taxonomy = taxonomy();
        return findAll().stream().map(category -> toResponse(category, taxonomy)).toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(String id) {
        return toResponse(findById(id), taxonomy());
    }

    /**
     * Creates a category <em>and</em> the relation row that places it in the tree,
     * in one transaction — a category with no edge would break the invariant that
     * every category has exactly one.
     *
     * @throws InvalidReferenceException if {@code superiorCategoryId} names no category
     * @throws ResourceConflictException if the name is already taken
     */
    @Transactional
    public CategoryResponse createCategory(CategoryRequest request) {
        CategoryEntity created = create(request.name(), category -> { });
        linkTo(created, resolveSuperior(request.superiorCategoryId(), created));
        return toResponse(created, taxonomy());
    }

    /**
     * Renames a category and re-places it in the tree. Both are full
     * replacements: an absent {@code superiorCategoryId} promotes the category to
     * a root rather than leaving its parent alone.
     *
     * @throws InvalidReferenceException if {@code superiorCategoryId} names no category
     * @throws InvalidInputException     if the requested parent is this category's own
     *                                   descendant, which would close a cycle
     * @throws ResourceConflictException if the new name is already taken
     */
    @Transactional
    public CategoryResponse updateCategory(String id, CategoryRequest request) {
        CategoryEntity updated = update(id, request.name(), category -> { });
        CategoryEntity superior = resolveSuperior(request.superiorCategoryId(), updated);
        rejectCycle(updated, superior);
        linkTo(updated, superior);
        return toResponse(updated, taxonomy());
    }

    /**
     * Every category below this one, at any depth, parents before their children
     * and name-sorted within each level.
     * <p>
     * Descendants and not just direct children: the route answers "show me
     * everything filed under desserts", and a caller that wants one level at a
     * time has {@code GET /categories/hierarchy}. The category itself is not
     * included — it is not below itself, and including it would make an empty
     * result impossible to distinguish from a leaf.
     *
     * @throws ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException
     *         if the id names no category, rather than an empty list — "this
     *         category has nothing under it" and "there is no such category" are
     *         different answers
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> findSubcategories(String id) {
        findById(id);
        CategoryTaxonomy taxonomy = taxonomy();
        return taxonomy.descendantsOf(id).stream()
                .map(category -> toResponse(category, taxonomy))
                .toList();
    }

    /**
     * The ancestor chain, <strong>nearest first</strong>, ending at the main
     * category. Empty for a main category, which has no superior but itself.
     * <p>
     * Nearest first because that is a breadcrumb reversed, and a breadcrumb is
     * what this feeds; a caller wanting root-first reverses a list it already
     * holds.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> findSuperiorCategories(String id) {
        findById(id);
        CategoryTaxonomy taxonomy = taxonomy();
        return taxonomy.ancestorsOf(id).stream()
                .map(category -> toResponse(category, taxonomy))
                .toList();
    }

    /**
     * The whole taxonomy as a forest of main categories, each carrying its
     * children nested, name-sorted at every level.
     */
    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> findHierarchy() {
        CategoryTaxonomy taxonomy = taxonomy();
        return taxonomy.roots().stream().map(root -> toTree(root, taxonomy)).toList();
    }

    /**
     * Writes the edge and the mirrored {@code category_type} together — the single
     * writer of both, which is the entire justification for storing the second
     * one.
     */
    private void linkTo(CategoryEntity category, CategoryEntity superior) {
        CategoryRelationEntity relation = relationsRepository.findById(category.getId())
                .orElseGet(CategoryRelationEntity::new);
        relation.setCategory(category);
        relation.setSuperiorCategory(superior);
        relationsRepository.save(relation);

        category.setCategoryType(superior.getId().equals(category.getId())
                ? CategoryType.MAIN : CategoryType.SUB);
        categoriesRepository.save(category);
        log.info("Category {} ('{}') is now {}, under {}", category.getId(), category.getName(),
                category.getCategoryType(), superior.getId());
    }

    /**
     * Resolves the requested parent, defaulting to the category itself — the
     * schema's spelling of "this is a root".
     *
     * @throws InvalidReferenceException if the id names no category. A 400 and not
     *                                   a 404: {@code /categories} is perfectly
     *                                   reachable, it is the payload that is wrong
     */
    private CategoryEntity resolveSuperior(String superiorCategoryId, CategoryEntity category) {
        if (superiorCategoryId == null || superiorCategoryId.isBlank()
                || superiorCategoryId.equals(category.getId())) {
            return category;
        }
        return categoriesRepository.findById(superiorCategoryId)
                .orElseThrow(() -> new InvalidReferenceException("superiorCategoryId",
                        List.of(superiorCategoryId)));
    }

    /**
     * Refuses to file a category under one of its own descendants.
     * <p>
     * It walks <em>down</em> from the category looking for the proposed parent
     * rather than up from the parent looking for the category. The second reads
     * more naturally but loops forever on a tree that is already cyclic — which is
     * exactly the state this check exists to keep out, and therefore the state it
     * has to survive.
     */
    private void rejectCycle(CategoryEntity category, CategoryEntity superior) {
        if (superior.getId().equals(category.getId())) {
            return;
        }
        boolean wouldLoop = taxonomy().descendantsOf(category.getId()).stream()
                .anyMatch(descendant -> descendant.getId().equals(superior.getId()));
        if (wouldLoop) {
            log.warn("Refusing to file category {} under its own descendant {}",
                    category.getId(), superior.getId());
            throw new InvalidInputException(
                    "A category cannot be placed under one of its own subcategories.");
        }
    }

    private CategoryTreeResponse toTree(CategoryEntity category, CategoryTaxonomy taxonomy) {
        return toTree(category, taxonomy, new LinkedHashSet<>());
    }

    /**
     * {@code visiting} is the same safeguard the walks in {@link CategoryTaxonomy}
     * carry: writes cannot create a cycle, but a row edited by hand in psql would
     * otherwise turn this {@code GET} into a non-terminating recursion, and a hang
     * is a worse failure than a truncated branch.
     */
    private CategoryTreeResponse toTree(CategoryEntity category, CategoryTaxonomy taxonomy,
                                        Set<String> visiting) {
        if (!visiting.add(category.getId())) {
            log.error("Category {} appears under itself; truncating the hierarchy there",
                    category.getId());
            return new CategoryTreeResponse(category.getId(), category.getName(), List.of());
        }
        return new CategoryTreeResponse(category.getId(), category.getName(),
                taxonomy.childrenOf(category.getId()).stream()
                        .map(child -> toTree(child, taxonomy, visiting))
                        .toList());
    }

    private CategoryTaxonomy taxonomy() {
        return new CategoryTaxonomy(findAll(), relationsRepository.superiorIdsByCategoryId());
    }

    private CategoryResponse toResponse(CategoryEntity category, CategoryTaxonomy taxonomy) {
        return mapper.toResponse(category, taxonomy.superiorIdOf(category.getId()));
    }
}
