package ilenreste.unpeu.recettesback.services.recipes;

import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.exceptions.InvalidReferenceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReferenceResolverTest {

    private final ReferenceResolver resolver = new ReferenceResolver();

    private CategoryEntity category(String id) {
        CategoryEntity entity = new CategoryEntity();
        entity.setId(id);
        entity.setName(id);
        return entity;
    }

    /** Records what the loader was asked for, so the test can assert it was called once. */
    private static final class RecordingLoader implements java.util.function.Function<Collection<String>, List<CategoryEntity>> {
        private final List<CategoryEntity> available;
        private final List<Collection<String>> calls = new ArrayList<>();

        RecordingLoader(List<CategoryEntity> available) {
            this.available = available;
        }

        @Override
        public List<CategoryEntity> apply(Collection<String> ids) {
            calls.add(ids);
            return available.stream().filter(entity -> ids.contains(entity.getId())).toList();
        }
    }

    @Test
    void loadsEveryIdInASingleCall() {
        // The reason this class exists: a findById per id is 30+ queries on a normal recipe.
        RecordingLoader loader = new RecordingLoader(
                List.of(category("a"), category("b"), category("c")));

        Map<String, CategoryEntity> resolved = resolver.resolve("categoryIds",
                List.of("a", "b", "c"), loader, CategoryEntity::getId);

        assertThat(loader.calls).hasSize(1);
        assertThat(resolved).containsOnlyKeys("a", "b", "c");
    }

    @Test
    void collapsesDuplicatesAndSkipsNullsAndBlanks() {
        RecordingLoader loader = new RecordingLoader(List.of(category("a")));

        resolver.resolve("categoryIds", Arrays.asList("a", "a", null, "  ", "a"),
                loader, CategoryEntity::getId);

        assertThat(loader.calls.getFirst()).containsExactly("a");
    }

    @Test
    void namesEveryMissingId_notJustTheFirst() {
        // A client with a stale list would otherwise fix one id per round trip.
        RecordingLoader loader = new RecordingLoader(List.of(category("a")));

        assertThatThrownBy(() -> resolver.resolve("categoryIds",
                List.of("a", "ghost-1", "ghost-2"), loader, CategoryEntity::getId))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("categoryIds")
                .hasMessageContaining("ghost-1")
                .hasMessageContaining("ghost-2");
    }

    @Test
    void namesTheFieldTheIdsCameFrom_soTheErrorIsActionable() {
        RecordingLoader loader = new RecordingLoader(List.of());

        assertThatThrownBy(() -> resolver.resolve("ingredientGroups[].ingredients[].unitId",
                List.of("ghost"), loader, CategoryEntity::getId))
                .hasMessageContaining("ingredientGroups[].ingredients[].unitId");
    }

    @Test
    void doesNotQueryAtAllWhenThereIsNothingToResolve() {
        RecordingLoader loader = new RecordingLoader(List.of());

        assertThat(resolver.resolve("categoryIds", List.of(), loader, CategoryEntity::getId)).isEmpty();
        assertThat(resolver.resolve("categoryIds", null, loader, CategoryEntity::getId)).isEmpty();
        assertThat(resolver.resolve("categoryIds", Arrays.asList((String) null), loader,
                CategoryEntity::getId)).isEmpty();

        assertThat(loader.calls).isEmpty();
    }
}
