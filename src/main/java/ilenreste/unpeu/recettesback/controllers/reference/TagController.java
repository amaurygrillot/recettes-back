package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.api.TagsApi;
import ilenreste.unpeu.recettesback.models.reference.requests.TagRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import ilenreste.unpeu.recettesback.services.reference.TagService;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Tags: open-ended facets. Reads public, writes admin-only.
 * <p>
 * The routes, parameters, status codes and security of every method here are
 * declared in {@code openapi.yaml} and reach this class through the generated
 * {@link TagsApi}; see {@code docs/openapi.md}.
 * <p>
 * No {@code try/catch} and no result checking: {@code TagService} returns a
 * response record or throws, and {@code ApiExceptionHandler} turns each typed
 * exception into its status — 404 for an unknown id, 409 for a duplicate name or
 * a tag a recipe still uses.
 */
@Log4j2
@RestController
public class TagController implements TagsApi {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @Override
    public ResponseEntity<List<TagResponse>> listTags() {
        return ResponseEntity.ok(tagService.list());
    }

    @Override
    public ResponseEntity<TagResponse> getTag(String id) {
        return ResponseEntity.ok(tagService.get(id));
    }

    @Override
    public ResponseEntity<TagResponse> createTag(TagRequest request) {
        log.info("Creating a tag");
        TagResponse created = tagService.createTag(request);
        return ResponseEntity.created(URI.create("/tags/" + created.id())).body(created);
    }

    @Override
    public ResponseEntity<TagResponse> updateTag(String id, TagRequest request) {
        log.info("Updating tag {}", id);
        return ResponseEntity.ok(tagService.updateTag(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @Override
    public ResponseEntity<Void> deleteTag(String id) {
        log.info("Deleting tag {}", id);
        tagService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
