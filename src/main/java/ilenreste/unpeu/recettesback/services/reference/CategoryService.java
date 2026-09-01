package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.models.reference.requests.ReferenceNameRequest;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CategoryService extends AbstractReferenceService<CategoryEntity> {

    private final CategoriesRepository categoriesRepository;
    private final RecipesRepository recipesRepository;

    public CategoryService(CategoriesRepository categoriesRepository, RecipesRepository recipesRepository,
                           ReferenceNameNormalizer normalizer) {
        super(categoriesRepository, normalizer);
        this.categoriesRepository = categoriesRepository;
        this.recipesRepository = recipesRepository;
    }

    @Override
    protected CategoryEntity newEntity() {
        return new CategoryEntity();
    }

    @Override
    protected String resourceName() {
        return "category";
    }

    @Override
    protected boolean isUsed(String id) {
        return recipesRepository.isCategoryUsed(id);
    }

    @Transactional
    public CategoryEntity createCategory(ReferenceNameRequest request) {
        return create(request.name());
    }

    @Transactional
    public CategoryEntity updateCategory(String id, ReferenceNameRequest request) {
        return categoriesRepository.save(rename(findById(id), request.name()));
    }
}
