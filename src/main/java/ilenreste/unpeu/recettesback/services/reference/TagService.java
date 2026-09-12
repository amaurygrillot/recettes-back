package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.TagRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.TagsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Tags: open-ended, flat facets.
 * <p>
 * Returns response records rather than entities, for the reason spelled out on
 * {@code CategoryService}: nothing above this layer should be able to reach the
 * persistence graph, least of all after the transaction has closed.
 */
@Service
public class TagService extends AbstractReferenceService<TagEntity> {

    private final RecipesRepository recipesRepository;
    private final ReferenceMapper mapper;

    public TagService(TagsRepository tagsRepository, RecipesRepository recipesRepository,
                      ReferenceNameNormalizer normalizer, ReferenceMapper mapper) {
        super(tagsRepository, normalizer);
        this.recipesRepository = recipesRepository;
        this.mapper = mapper;
    }

    @Override
    protected TagEntity newEntity() {
        return new TagEntity();
    }

    @Override
    protected String resourceName() {
        return "tag";
    }

    @Override
    protected boolean isUsed(String id) {
        return recipesRepository.isTagUsed(id);
    }

    @Transactional(readOnly = true)
    public List<TagResponse> list() {
        return mapper.toTagResponses(findAll());
    }

    @Transactional(readOnly = true)
    public TagResponse get(String id) {
        return mapper.toResponse(findById(id));
    }

    @Transactional
    public TagResponse createTag(TagRequest request) {
        return mapper.toResponse(create(request.name(), tag -> { }));
    }

    @Transactional
    public TagResponse updateTag(String id, TagRequest request) {
        return mapper.toResponse(update(id, request.name(), tag -> { }));
    }
}
