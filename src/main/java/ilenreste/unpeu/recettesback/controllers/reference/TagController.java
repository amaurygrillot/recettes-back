package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.models.reference.requests.TagRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import ilenreste.unpeu.recettesback.services.reference.TagService;
import jakarta.validation.Valid;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Tags: open-ended facets. Reads public, writes admin-only.
 * <p>
 * No {@code try/catch} and no result checking: {@code TagService} returns a
 * response record or throws, and {@code ApiExceptionHandler} turns each typed
 * exception into its status — 404 for an unknown id, 409 for a duplicate name or
 * a tag a recipe still uses.
 */
@Log4j2
@RequestMapping("/tags")
@RestController
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public ResponseEntity<List<TagResponse>> list() {
        return ResponseEntity.ok(tagService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TagResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(tagService.get(id));
    }

    @PostMapping
    public ResponseEntity<TagResponse> create(@Valid @RequestBody TagRequest request) {
        log.info("Creating a tag");
        TagResponse created = tagService.createTag(request);
        return ResponseEntity.created(URI.create("/tags/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TagResponse> update(@PathVariable String id,
                                              @Valid @RequestBody TagRequest request) {
        log.info("Updating tag {}", id);
        return ResponseEntity.ok(tagService.updateTag(id, request));
    }

    /** 409 rather than a cascade if any recipe still uses it. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        log.info("Deleting tag {}", id);
        tagService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
