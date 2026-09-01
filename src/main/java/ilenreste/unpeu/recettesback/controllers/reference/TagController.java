package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.ReferenceNameRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import ilenreste.unpeu.recettesback.services.reference.TagService;
import jakarta.validation.Valid;
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

/** Tags: open-ended facets. Reads public, writes admin-only. */
@RequestMapping("/tags")
@RestController
public class TagController {

    private final TagService tagService;
    private final ReferenceMapper mapper;

    public TagController(TagService tagService, ReferenceMapper mapper) {
        this.tagService = tagService;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<List<TagResponse>> list() {
        return ResponseEntity.ok(mapper.toTagResponses(tagService.findAll()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TagResponse> get(@PathVariable String id) {
        return ResponseEntity.ok(mapper.toResponse(tagService.findById(id)));
    }

    @PostMapping
    public ResponseEntity<TagResponse> create(@Valid @RequestBody ReferenceNameRequest request) {
        TagEntity created = tagService.createTag(request);
        return ResponseEntity.created(URI.create("/tags/" + created.getId()))
                .body(mapper.toResponse(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TagResponse> update(@PathVariable String id,
                                              @Valid @RequestBody ReferenceNameRequest request) {
        return ResponseEntity.ok(mapper.toResponse(tagService.updateTag(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        tagService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
