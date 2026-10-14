package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientSearchRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * The one reference table any authenticated user may add to, because a recipe
 * cannot be written without the ingredients it uses.
 * <p>
 * Editing and deleting stay admin-only (enforced by the URL rules in
 * {@code SecurityFilterConfig}): renaming a shared row silently rewrites every
 * recipe referencing it, and deleting one would orphan them. That is a different
 * kind of power from adding a missing row.
 * <p>
 * Returns response records rather than entities, for the reason spelled out on
 * {@code CategoryService}.
 */
@Service
public class IngredientService extends AbstractReferenceService<IngredientEntity, IngredientResponse> {

    private final IngredientsRepository ingredientsRepository;
    private final RecipesRepository recipesRepository;
    private final MediaRepository mediaRepository;
    private final ReferenceMapper mapper;

    public IngredientService(IngredientsRepository ingredientsRepository, RecipesRepository recipesRepository,
                             MediaRepository mediaRepository, ReferenceNameNormalizer normalizer,
                             ReferenceMapper mapper) {
        super(ingredientsRepository, normalizer);
        this.ingredientsRepository = ingredientsRepository;
        this.recipesRepository = recipesRepository;
        this.mediaRepository = mediaRepository;
        this.mapper = mapper;
    }

    @Override
    protected IngredientEntity newEntity() {
        return new IngredientEntity();
    }

    @Override
    protected String resourceName() {
        return "ingredient";
    }

    @Override
    protected boolean isUsed(String id) {
        return recipesRepository.isIngredientUsed(id);
    }

    /**
     * Prefix search for the recipe editor's autocomplete, or everything when no
     * prefix is given.
     * <p>
     * The search runs against {@code normalized_name}, which is what makes typing
     * {@code oeuf} find {@code Œuf}.
     * <p>
     * <strong>The sort is rebuilt here, not taken from the incoming
     * {@code Pageable}.</strong> The {@code Pageable} contributes the page number
     * and size; the ordering comes from {@link IngredientSearchRequest#sort()},
     * which can only name a column in the {@code IngredientSortField} enum. That
     * is what stops a query string choosing an arbitrary entity property — see
     * that enum for the 500 and the password-column ordering this closes. Same
     * shape as {@code RecipeService.search}.
     */
    @Transactional(readOnly = true)
    public Page<IngredientResponse> search(IngredientSearchRequest request, Pageable pageable) {
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), request.sort());

        String namePrefix = request.namePrefix();
        Page<IngredientEntity> page = namePrefix == null
                ? ingredientsRepository.findAll(sorted)
                : ingredientsRepository.findByNormalizedNameStartingWith(
                        normalizer.normalize(namePrefix), sorted);
        return page.map(mapper::toResponse);
    }

    @Override
    protected IngredientResponse toResponse(IngredientEntity ingredient) {
        return mapper.toResponse(ingredient);
    }

    @Transactional
    public IngredientResponse createIngredient(IngredientRequest request) {
        return mapper.toResponse(create(request.name(),
                ingredient -> ingredient.setIcon(resolveIcon(request.iconMediaId()))));
    }

    @Transactional
    public IngredientResponse updateIngredient(String id, IngredientRequest request) {
        // A null id clears the icon: this is a full update, not a patch, so an absent icon means
        // "no icon" rather than "leave it alone".
        return mapper.toResponse(update(id, request.name(),
                ingredient -> ingredient.setIcon(resolveIcon(request.iconMediaId()))));
    }

    /**
     * @throws InvalidReferenceException if the id names no media row — a 400,
     *                                   because {@code /ingredients} is perfectly
     *                                   reachable and it is the payload that is
     *                                   wrong
     */
    private MediaEntity resolveIcon(String mediaId) {
        if (!StringUtils.hasText(mediaId)) {
            return null;
        }
        return mediaRepository.findById(mediaId)
                .orElseThrow(() -> new InvalidReferenceException("iconMediaId", List.of(mediaId)));
    }
}
