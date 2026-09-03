package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
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
 */
@Service
public class IngredientService extends AbstractReferenceService<IngredientEntity> {

    private final IngredientsRepository ingredientsRepository;
    private final RecipesRepository recipesRepository;
    private final MediaRepository mediaRepository;

    public IngredientService(IngredientsRepository ingredientsRepository, RecipesRepository recipesRepository,
                             MediaRepository mediaRepository, ReferenceNameNormalizer normalizer) {
        super(ingredientsRepository, normalizer);
        this.ingredientsRepository = ingredientsRepository;
        this.recipesRepository = recipesRepository;
        this.mediaRepository = mediaRepository;
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
     * Prefix search for the recipe editor's autocomplete, or everything when
     * {@code q} is absent.
     * <p>
     * The search runs against {@code normalized_name}, which is what makes typing
     * {@code oeuf} find {@code Œuf}. Results are name-sorted for a stable list.
     */
    public Page<IngredientEntity> search(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return ingredientsRepository.findAll(pageable);
        }
        return ingredientsRepository.findByNormalizedNameStartingWithOrderByName(
                normalizer.normalize(q), pageable);
    }

    @Transactional
    public IngredientEntity createIngredient(IngredientRequest request) {
        IngredientEntity ingredient = create(request.name());
        ingredient.setIcon(resolveIcon(request.iconMediaId()));
        return ingredient;
    }

    @Transactional
    public IngredientEntity updateIngredient(String id, IngredientRequest request) {
        IngredientEntity ingredient = rename(findById(id), request.name());
        // A null id clears the icon: this is a full update, not a patch, so an absent icon means
        // "no icon" rather than "leave it alone".
        ingredient.setIcon(resolveIcon(request.iconMediaId()));
        return ingredientsRepository.save(ingredient);
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
