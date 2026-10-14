package ilenreste.unpeu.recettesback.repositories.reference;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryRelationEntity;
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findChildrenOf} is hand-written JPQL, so it runs against the real PostgreSQL — see
 * {@code RecipesRepositoryTest} for why, and for why both imports are needed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, CurrentUserService.class})
class CategoryRelationsRepositoryTest {

    @Autowired
    private CategoriesRepository categoriesRepository;
    @Autowired
    private CategoryRelationsRepository relationsRepository;

    /** The random suffix keeps repeated runs from colliding on the unique normalized name. */
    private CategoryEntity category(String name) {
        CategoryEntity entity = new CategoryEntity();
        entity.setName(name);
        entity.setNormalizedName(name + "-" + UUID.randomUUID());
        return categoriesRepository.saveAndFlush(entity);
    }

    /** A root is its own parent; that is how the schema keeps {@code parent_category_id} NOT NULL. */
    private void relate(CategoryEntity category, CategoryEntity parent) {
        CategoryRelationEntity relation = new CategoryRelationEntity();
        relation.setCategory(category);
        relation.setParentCategory(parent);
        relationsRepository.saveAndFlush(relation);
    }

    @Test
    void findChildrenOfReturnsDirectChildrenOnlyAndNotTheRootItself() {
        CategoryEntity desserts = category("desserts");
        CategoryEntity tartes = category("tartes");
        CategoryEntity glaces = category("glaces");
        CategoryEntity tarteAuCitron = category("tarte-au-citron");
        CategoryEntity plats = category("plats");
        relate(desserts, desserts);
        relate(tartes, desserts);
        relate(glaces, desserts);
        relate(tarteAuCitron, tartes);
        relate(plats, plats);

        assertThat(relationsRepository.findChildrenOf(desserts.getId()))
                .extracting(CategoryEntity::getId)
                .containsExactlyInAnyOrder(tartes.getId(), glaces.getId());
        assertThat(relationsRepository.findChildrenOf(tartes.getId()))
                .extracting(CategoryEntity::getId)
                .containsExactly(tarteAuCitron.getId());
    }

    @Test
    void findChildrenOfIsEmptyForALeafAChildlessRootAndAnUnknownId() {
        CategoryEntity plats = category("plats");
        CategoryEntity soupes = category("soupes");
        relate(plats, plats);
        relate(soupes, plats);

        assertThat(relationsRepository.findChildrenOf(soupes.getId())).isEmpty();
        assertThat(relationsRepository.findChildrenOf("no-such-category")).isEmpty();

        CategoryEntity childlessRoot = category("boissons");
        relate(childlessRoot, childlessRoot);
        assertThat(relationsRepository.findChildrenOf(childlessRoot.getId())).isEmpty();
    }

    @Test
    void findChildrenOfIsOrderedByNormalizedName() {
        CategoryEntity root = category("desserts");
        CategoryEntity b = category("b-tartes");
        CategoryEntity a = category("a-glaces");
        relate(root, root);
        relate(b, root);
        relate(a, root);

        assertThat(relationsRepository.findChildrenOf(root.getId()))
                .extracting(CategoryEntity::getId)
                .containsExactly(a.getId(), b.getId());
    }
}
