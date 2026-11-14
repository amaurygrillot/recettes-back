package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.api.RecipeListsApi;
import ilenreste.unpeu.recettesback.controllers.media.MediaResponses;
import ilenreste.unpeu.recettesback.models.media.MediaVariant;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeListRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeListResponse;
import ilenreste.unpeu.recettesback.services.media.StoredMedia;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListCoverService;
import ilenreste.unpeu.recettesback.services.recipes.RecipeListService;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * A member's private recipe lists. Every route needs a login, and a list is only ever visible to its owner.
 * <p>
 * The routes, parameters, statuses and security of every method here are declared in {@code openapi.yaml}
 * and reach this class through the generated {@link RecipeListsApi}; see {@code docs/openapi.md}. The
 * content of a list is not served here: it is read through {@code GET /recipes?listId=}, whose guard is
 * {@code RecipeListService.requireVisible}.
 * <p>
 * Nothing here holds a {@code try/catch} or checks a result. {@code RecipeListService} returns a response
 * record or throws, and {@code ApiExceptionHandler} maps each typed exception: 404 for a list that does not
 * exist or is not the caller's (the same answer for both, so an id is never confirmed), 409 for a name
 * already used and for the two limits, told apart by the problem's {@code type}. The service reads the caller
 * from {@code CurrentUserService}, so no method here takes an identity.
 * <p>
 * The list's own photo ({@code /lists/{id}/cover}) is served and written through {@link RecipeListCoverService}; its
 * headers are those of {@code GET /media/{id}}, built by the same {@link MediaResponses}, and only the
 * {@code Cache-Control} differs. See {@code docs/recipe-list-cover.md}.
 */
@RestController
public class RecipeListController implements RecipeListsApi {

    /**
     * <strong>{@code private}, never {@code public}</strong>: a shared cache must not keep a list's photo. Without a
     * version the browser may keep the photo but has to revalidate it with its {@code ETag}.
     */
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    /** With the list's current version in the URL, the bytes behind it can never change: a new photo is a new version. */
    private static final CacheControl IMMUTABLE = CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable();

    private final RecipeListService recipeListService;
    private final RecipeListCoverService coverService;

    public RecipeListController(RecipeListService recipeListService, RecipeListCoverService coverService) {
        this.recipeListService = recipeListService;
        this.coverService = coverService;
    }

    @Override
    public ResponseEntity<List<RecipeListResponse>> listRecipeLists(String containing) {
        return ResponseEntity.ok(recipeListService.list(containing));
    }

    @Override
    public ResponseEntity<RecipeListResponse> createRecipeList(RecipeListRequest request) {
        RecipeListResponse created = recipeListService.create(request);
        return ResponseEntity.created(URI.create("/lists/" + created.id())).body(created);
    }

    @Override
    public ResponseEntity<RecipeListResponse> getRecipeList(String id) {
        return ResponseEntity.ok(recipeListService.get(id));
    }

    @Override
    public ResponseEntity<RecipeListResponse> updateRecipeList(String id, RecipeListRequest request) {
        return ResponseEntity.ok(recipeListService.update(id, request));
    }

    @Override
    public ResponseEntity<Void> deleteRecipeList(String id) {
        recipeListService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> addRecipeToList(String id, String recipeId) {
        recipeListService.addRecipe(id, recipeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> removeRecipeFromList(String id, String recipeId) {
        recipeListService.removeRecipe(id, recipeId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RecipeListResponse> setRecipeListCover(String id, MultipartFile file) {
        return ResponseEntity.ok(coverService.replace(id, file));
    }

    @Override
    public ResponseEntity<RecipeListResponse> removeRecipeListCover(String id) {
        return ResponseEntity.ok(coverService.remove(id));
    }

    /**
     * The service decides who may read, and answers 404 for a stale {@code v}, <em>before</em> this method compares
     * {@code If-None-Match}: another member who sends a matching tag gets 404, never 304. Reaching the cache policy
     * below with a {@code v} therefore means it is the current one.
     */
    @Override
    public ResponseEntity<Resource> downloadRecipeListCover(String id, String v, String variant,
                                                            WebRequest webRequest) {
        StoredMedia cover = coverService.load(id, MediaVariant.from(variant), v);
        return MediaResponses.download(cover, StringUtils.hasText(v) ? IMMUTABLE : REVALIDATE, webRequest);
    }
}
