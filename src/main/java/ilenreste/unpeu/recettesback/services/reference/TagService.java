package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.models.reference.requests.ReferenceNameRequest;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.TagsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TagService extends AbstractReferenceService<TagEntity> {

    private final TagsRepository tagsRepository;
    private final RecipesRepository recipesRepository;

    public TagService(TagsRepository tagsRepository, RecipesRepository recipesRepository,
                      ReferenceNameNormalizer normalizer) {
        super(tagsRepository, normalizer);
        this.tagsRepository = tagsRepository;
        this.recipesRepository = recipesRepository;
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

    @Transactional
    public TagEntity createTag(ReferenceNameRequest request) {
        return create(request.name());
    }

    @Transactional
    public TagEntity updateTag(String id, ReferenceNameRequest request) {
        return tagsRepository.save(rename(findById(id), request.name()));
    }
}
