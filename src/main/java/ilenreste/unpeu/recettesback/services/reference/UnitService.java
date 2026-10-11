package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.mappers.reference.ReferenceMapper;
import ilenreste.unpeu.recettesback.models.reference.requests.UnitRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Units of measure. Admin-curated by analogy with categories and tags: a small
 * closed set that shapes how every recipe reads, and not something a user
 * legitimately needs to extend mid-recipe.
 * <p>
 * {@code abbreviation} is set through the customizer the base class runs
 * <em>before</em> the insert, not by mutating the row afterwards and trusting
 * dirty checking to notice. The difference only shows the day somebody marks the
 * column {@code NOT NULL}, and then it shows as a constraint violation from code
 * that reads as if it works.
 */
@Service
public class UnitService extends AbstractReferenceService<UnitEntity, UnitResponse> {

    private final RecipesRepository recipesRepository;
    private final ReferenceMapper mapper;

    public UnitService(UnitsRepository unitsRepository, RecipesRepository recipesRepository,
                       ReferenceNameNormalizer normalizer, ReferenceMapper mapper) {
        super(unitsRepository, normalizer);
        this.recipesRepository = recipesRepository;
        this.mapper = mapper;
    }

    @Override
    protected UnitEntity newEntity() {
        return new UnitEntity();
    }

    @Override
    protected String resourceName() {
        return "unit";
    }

    @Override
    protected boolean isUsed(String id) {
        return recipesRepository.isUnitUsed(id);
    }

    @Override
    protected UnitResponse toResponse(UnitEntity unit) {
        return mapper.toResponse(unit);
    }

    @Transactional
    public UnitResponse createUnit(UnitRequest request) {
        return mapper.toResponse(
                create(request.name(), unit -> unit.setAbbreviation(request.abbreviation())));
    }

    @Transactional
    public UnitResponse updateUnit(String id, UnitRequest request) {
        return mapper.toResponse(
                update(id, request.name(), unit -> unit.setAbbreviation(request.abbreviation())));
    }
}
