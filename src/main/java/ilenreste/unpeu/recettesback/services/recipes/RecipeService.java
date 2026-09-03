package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientGroupEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeStepEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeStepPictureEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
import ilenreste.unpeu.recettesback.exceptions.ResourceNotFoundException;
import ilenreste.unpeu.recettesback.mappers.recipes.RecipeMapper;
import ilenreste.unpeu.recettesback.models.recipes.requests.CreateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeIngredientGroupRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeIngredientRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipePictureRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeStepRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.TagsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Log4j2
@Service
public class RecipeService {

    private final RecipesRepository recipesRepository;
    private final CategoriesRepository categoriesRepository;
    private final TagsRepository tagsRepository;
    private final IngredientsRepository ingredientsRepository;
    private final UnitsRepository unitsRepository;
    private final MediaRepository mediaRepository;
    private final ReferenceResolver referenceResolver;
    private final CurrentUserService currentUserService;
    private final RecipeMapper mapper;

    public RecipeService(RecipesRepository recipesRepository, CategoriesRepository categoriesRepository,
                         TagsRepository tagsRepository, IngredientsRepository ingredientsRepository,
                         UnitsRepository unitsRepository, MediaRepository mediaRepository,
                         ReferenceResolver referenceResolver, CurrentUserService currentUserService,
                         RecipeMapper mapper) {
        this.recipesRepository = recipesRepository;
        this.categoriesRepository = categoriesRepository;
        this.tagsRepository = tagsRepository;
        this.ingredientsRepository = ingredientsRepository;
        this.unitsRepository = unitsRepository;
        this.mediaRepository = mediaRepository;
        this.referenceResolver = referenceResolver;
        this.currentUserService = currentUserService;
        this.mapper = mapper;
    }

    /**
     * Pages over ids, then loads exactly that page.
     * <p>
     * <strong>The reordering is not optional.</strong> {@code findAllForSummary}
     * uses {@code IN}, which does not preserve order, so its rows come back in
     * whatever order PostgreSQL likes. Mapping them straight through silently
     * discards the sort the caller asked for — and the page still has the right
     * rows and the right count, so nothing looks wrong until someone notices the
     * "newest first" list is not.
     */
    @Transactional(readOnly = true)
    public Page<RecipeSummaryResponse> search(String authorId, String categoryId, String tagId,
                                              String q, Pageable pageable) {
        Page<String> idPage = recipesRepository.searchIds(
                blankToNull(authorId), blankToNull(categoryId), blankToNull(tagId), blankToNull(q), pageable);
        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }

        Map<String, RecipeEntity> byId = recipesRepository.findAllForSummary(idPage.getContent()).stream()
                .collect(java.util.stream.Collectors.toMap(RecipeEntity::getId, Function.identity()));

        List<RecipeSummaryResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(mapper::toSummary)
                .toList();

        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    @Transactional(readOnly = true)
    public RecipeResponse findById(String id) {
        return mapper.toResponse(loadRecipe(id));
    }

    @Transactional
    public RecipeResponse create(CreateRecipeRequest request) {
        RecipeEntity recipe = new RecipeEntity();
        recipe.setTitle(request.title().trim());
        recipe.setRecommendations(request.recommendations());
        // The author is not taken from the body - it is stamped by JPA auditing from the security
        // context, so a recipe cannot be posted under someone else's name.

        recipe.getCategories().addAll(resolveCategories(request.categoryIds()));
        recipe.getTags().addAll(resolveTags(request.tagIds()));
        replaceCoverPictures(recipe, request.coverPictures());
        replaceIngredientGroups(recipe, request.ingredientGroups());
        replaceSteps(recipe, request.steps());

        RecipeEntity saved = recipesRepository.save(recipe);
        log.info("Created recipe {}", saved.getId());
        return mapper.toResponse(saved);
    }

    /**
     * Partial update: an absent field is left alone, a present collection
     * replaces the whole collection.
     */
    @Transactional
    public RecipeResponse update(String id, UpdateRecipeRequest request) {
        RecipeEntity recipe = loadRecipe(id);
        requireAuthorOrAdmin(recipe.getAuthor().getId(), id);

        request.title().ifPresent(title -> recipe.setTitle(title.trim()));
        request.recommendations().ifPresent(recipe::setRecommendations);
        request.categoryIds().ifPresent(ids -> replaceAll(recipe.getCategories(), resolveCategories(ids)));
        request.tagIds().ifPresent(ids -> replaceAll(recipe.getTags(), resolveTags(ids)));
        request.coverPictures().ifPresent(pictures -> replaceCoverPictures(recipe, pictures));
        request.ingredientGroups().ifPresent(groups -> replaceIngredientGroups(recipe, groups));
        request.steps().ifPresent(steps -> replaceSteps(recipe, steps));

        RecipeEntity saved = recipesRepository.save(recipe);
        log.info("Updated recipe {}", id);
        return mapper.toResponse(saved);
    }

    /**
     * Checks permission and existence in one scalar query rather than loading a
     * recipe that is about to be thrown away.
     * <p>
     * Deletion cascades to steps, groups, ingredient lines and picture links, and
     * merely dereferences ingredients, tags, categories and media.
     */
    @Transactional
    public void delete(String id) {
        String authorId = recipesRepository.findAuthorIdById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("recipe", id));
        requireAuthorOrAdmin(authorId, id);

        recipesRepository.deleteById(id);
        log.info("Deleted recipe {}", id);
    }

    /**
     * The rule a URL matcher cannot express: "you may edit this recipe because
     * you wrote it" depends on a row in the database.
     * <p>
     * It lives in the service, not the controller, so a second caller reaching
     * the same operation another way cannot bypass it.
     */
    private void requireAuthorOrAdmin(String authorId, String recipeId) {
        String currentUserId = currentUserService.currentUserId()
                .orElseThrow(() -> new ForbiddenOperationException(
                        "You must be signed in to modify a recipe."));
        if (!currentUserId.equals(authorId) && !currentUserService.isAdmin()) {
            log.warn("User {} attempted to modify recipe {} authored by {}",
                    currentUserId, recipeId, authorId);
            throw new ForbiddenOperationException("Only the author of this recipe may modify it.");
        }
    }

    private RecipeEntity loadRecipe(String id) {
        return recipesRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("recipe", id));
    }

    private List<CategoryEntity> resolveCategories(Collection<String> ids) {
        return List.copyOf(referenceResolver.resolve("categoryIds", ids,
                categoriesRepository::findAllById, CategoryEntity::getId).values());
    }

    private List<TagEntity> resolveTags(Collection<String> ids) {
        return List.copyOf(referenceResolver.resolve("tagIds", ids,
                tagsRepository::findAllById, TagEntity::getId).values());
    }

    /**
     * Mutates the existing collection in place rather than assigning a new one:
     * reassigning a Hibernate-managed collection throws.
     */
    private <T> void replaceAll(Collection<T> managed, Collection<T> replacement) {
        managed.clear();
        managed.addAll(replacement);
    }

    private void replaceCoverPictures(RecipeEntity recipe, List<RecipePictureRequest> pictures) {
        List<RecipePictureRequest> requested = pictures == null ? List.of() : pictures;
        Map<String, MediaEntity> media = resolveMedia("coverPictures[].mediaId", requested);

        // orphanRemoval on the association turns this clear() into DELETEs for the rows dropped.
        recipe.getCoverPictures().clear();
        for (int position = 0; position < requested.size(); position++) {
            RecipePictureRequest request = requested.get(position);
            RecipeCoverPictureEntity cover = new RecipeCoverPictureEntity();
            cover.setRecipe(recipe);
            cover.setMedia(media.get(request.mediaId()));
            cover.setAltText(request.altText());
            // Renumbered 0..n-1 from the order of the incoming array, which is the contract.
            cover.setPosition(position);
            recipe.getCoverPictures().add(cover);
        }
    }

    private void replaceSteps(RecipeEntity recipe, List<RecipeStepRequest> steps) {
        List<RecipeStepRequest> requested = steps == null ? List.of() : steps;
        List<RecipePictureRequest> allPictures = requested.stream()
                .flatMap(step -> step.pictures() == null ? java.util.stream.Stream.<RecipePictureRequest>of()
                        : step.pictures().stream())
                .toList();
        Map<String, MediaEntity> media = resolveMedia("steps[].pictures[].mediaId", allPictures);

        recipe.getSteps().clear();
        for (int position = 0; position < requested.size(); position++) {
            RecipeStepRequest request = requested.get(position);
            RecipeStepEntity step = new RecipeStepEntity();
            step.setRecipe(recipe);
            step.setPosition(position);
            step.setInstruction(request.instruction());

            List<RecipePictureRequest> stepPictures =
                    request.pictures() == null ? List.of() : request.pictures();
            for (int picturePosition = 0; picturePosition < stepPictures.size(); picturePosition++) {
                RecipePictureRequest picture = stepPictures.get(picturePosition);
                RecipeStepPictureEntity entity = new RecipeStepPictureEntity();
                entity.setStep(step);
                entity.setMedia(media.get(picture.mediaId()));
                entity.setAltText(picture.altText());
                entity.setPosition(picturePosition);
                step.getPictures().add(entity);
            }
            recipe.getSteps().add(step);
        }
    }

    private void replaceIngredientGroups(RecipeEntity recipe, List<RecipeIngredientGroupRequest> groups) {
        List<RecipeIngredientGroupRequest> requested = groups == null ? List.of() : groups;
        List<RecipeIngredientRequest> allLines = requested.stream()
                .flatMap(group -> group.ingredients() == null
                        ? java.util.stream.Stream.<RecipeIngredientRequest>of()
                        : group.ingredients().stream())
                .toList();

        // One query for every ingredient across every group, and one for every unit - not one per
        // line, which on a normal recipe is the difference between two queries and thirty.
        Map<String, IngredientEntity> ingredients = referenceResolver.resolve(
                "ingredientGroups[].ingredients[].ingredientId",
                allLines.stream().map(RecipeIngredientRequest::ingredientId).toList(),
                ingredientsRepository::findAllById, IngredientEntity::getId);
        Map<String, UnitEntity> units = referenceResolver.resolve(
                "ingredientGroups[].ingredients[].unitId",
                allLines.stream().map(RecipeIngredientRequest::unitId).toList(),
                unitsRepository::findAllById, UnitEntity::getId);

        recipe.getIngredientGroups().clear();
        for (int position = 0; position < requested.size(); position++) {
            RecipeIngredientGroupRequest request = requested.get(position);
            RecipeIngredientGroupEntity group = new RecipeIngredientGroupEntity();
            group.setRecipe(recipe);
            group.setPosition(position);
            group.setTitle(request.title());

            List<RecipeIngredientRequest> lines =
                    request.ingredients() == null ? List.of() : request.ingredients();
            for (int linePosition = 0; linePosition < lines.size(); linePosition++) {
                RecipeIngredientRequest line = lines.get(linePosition);
                RecipeIngredientEntity entity = new RecipeIngredientEntity();
                entity.setGroup(group);
                entity.setIngredient(ingredients.get(line.ingredientId()));
                entity.setQuantity(line.quantity());
                entity.setUnit(line.unitId() == null || line.unitId().isBlank()
                        ? null : units.get(line.unitId()));
                entity.setNote(line.note());
                entity.setPosition(linePosition);
                group.getIngredients().add(entity);
            }
            recipe.getIngredientGroups().add(group);
        }
    }

    private Map<String, MediaEntity> resolveMedia(String field, List<RecipePictureRequest> pictures) {
        return referenceResolver.resolve(field,
                new ArrayList<>(pictures.stream().map(RecipePictureRequest::mediaId).toList()),
                mediaRepository::findAllById, MediaEntity::getId);
    }

    /** A blank query parameter means "no filter", not "match the empty string". */
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
