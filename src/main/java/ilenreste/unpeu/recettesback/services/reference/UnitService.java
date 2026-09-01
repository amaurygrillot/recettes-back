package ilenreste.unpeu.recettesback.services.reference;

import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.models.reference.requests.UnitRequest;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitService extends AbstractReferenceService<UnitEntity> {

    private final UnitsRepository unitsRepository;
    private final RecipesRepository recipesRepository;

    public UnitService(UnitsRepository unitsRepository, RecipesRepository recipesRepository,
                       ReferenceNameNormalizer normalizer) {
        super(unitsRepository, normalizer);
        this.unitsRepository = unitsRepository;
        this.recipesRepository = recipesRepository;
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

    @Transactional
    public UnitEntity createUnit(UnitRequest request) {
        UnitEntity unit = create(request.name());
        unit.setAbbreviation(request.abbreviation());
        return unit;
    }

    @Transactional
    public UnitEntity updateUnit(String id, UnitRequest request) {
        UnitEntity unit = rename(findById(id), request.name());
        unit.setAbbreviation(request.abbreviation());
        return unitsRepository.save(unit);
    }
}
