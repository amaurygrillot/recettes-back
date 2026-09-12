package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
public class IngredientService extends AbstractReferenceService<IngredientEntity> {

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
     * {@code oeuf} find {@code Œuf}. Results are name-sorted for a stable list.
     *
     * @param namePrefix what the user has typed so far, or null/blank for no filter
     */
    @Transactional(readOnly = true)
    public Page<IngredientResponse> search(String namePrefix, Pageable pageable) {
        Page<IngredientEntity> page = namePrefix == null || namePrefix.isBlank()
                ? ingredientsRepository.findAll(pageable)
                : ingredientsRepository.findByNormalizedNameStartingWithOrderByName(
                        normalizer.normalize(namePrefix), pageable);
        return page.map(mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public IngredientResponse get(String id) {
        return mapper.toResponse(findById(id));
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
        if (mediaId == null || mediaId.isBlank()) {
            return null;
        }
        return mediaRepository.findById(mediaId)
                .orElseThrow(() -> new InvalidReferenceException("iconMediaId", List.of(mediaId)));
    }
}
