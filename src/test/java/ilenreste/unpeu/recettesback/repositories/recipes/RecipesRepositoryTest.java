package ilenreste.unpeu.recettesback.repositories.recipes;

import ilenreste.unpeu.recettesback.configuration.JpaAuditingConfig;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeIngredientGroupEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeStepEntity;
import ilenreste.unpeu.recettesback.entities.reference.CategoryEntity;
import ilenreste.unpeu.recettesback.entities.reference.IngredientEntity;
import ilenreste.unpeu.recettesback.entities.reference.TagEntity;
import ilenreste.unpeu.recettesback.entities.reference.UnitEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.reference.CategoriesRepository;
import ilenreste.unpeu.recettesback.repositories.reference.IngredientsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.TagsRepository;
import ilenreste.unpeu.recettesback.repositories.reference.UnitsRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code @Query} in {@link RecipesRepository} is hand-written JPQL, and a mocked repository
 * proves nothing about JPQL that does not compile or a filter that returns the wrong rows.
 * <p>
 * Runs against the <strong>real</strong> PostgreSQL rather than H2: the whole point is catching
 * wrong SQL, and a green result on a different engine is weaker evidence. {@code @DataJpaTest} is
 * transactional and rolls back, so the development database is not mutated.
 * <p>
 * {@link JpaAuditingConfig} is imported explicitly because {@code @DataJpaTest} does not load
 * arbitrary {@code @Configuration} classes — without it {@code @CreatedBy} never fires and every
 * insert dies on {@code recipes.author_id NOT NULL}, which is the same trap the media entity has.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class RecipesRepositoryTest {

    @Autowired
    private RecipesRepository recipesRepository;
    @Autowired
    private CategoriesRepository categoriesRepository;
    @Autowired
    private TagsRepository tagsRepository;
    @Autowired
    private UnitsRepository unitsRepository;
    @Autowired
    private IngredientsRepository ingredientsRepository;
    @Autowired
    private UsersRepository usersRepository;

    private UserEntity author;
    private UserEntity otherAuthor;

    @BeforeEach
    void setUp() {
        author = persistUser("author");
        otherAuthor = persistUser("other");
        authenticateAs(author);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private UserEntity persistUser(String prefix) {
        UserEntity user = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user.setUsername(prefix + "-" + suffix);
        user.setEmail(prefix + "-" + suffix + "@example.com");
        user.setPassword("hash");
        user.setEnabled(true);
        return usersRepository.saveAndFlush(user);
    }

    /** RecipeEntity.author is @CreatedBy, so the security context is what decides it. */
    private void authenticateAs(UserEntity user) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", user.getId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private CategoryEntity category(String name) {
        CategoryEntity entity = new CategoryEntity();
        entity.setName(name);
        entity.setNormalizedName(name + "-" + UUID.randomUUID());
        return categoriesRepository.saveAndFlush(entity);
    }

    private TagEntity tag(String name) {
        TagEntity entity = new TagEntity();
        entity.setName(name);
        entity.setNormalizedName(name + "-" + UUID.randomUUID());
        return tagsRepository.saveAndFlush(entity);
    }

    private UnitEntity unit(String name) {
        UnitEntity entity = new UnitEntity();
        entity.setName(name);
        entity.setNormalizedName(name + "-" + UUID.randomUUID());
        return unitsRepository.saveAndFlush(entity);
    }

    private IngredientEntity ingredient(String name) {
        IngredientEntity entity = new IngredientEntity();
        entity.setName(name);
        entity.setNormalizedName(name + "-" + UUID.randomUUID());
        return ingredientsRepository.saveAndFlush(entity);
    }

    private RecipeEntity recipe(String title, List<CategoryEntity> categories, List<TagEntity> tags) {
        RecipeEntity recipe = new RecipeEntity();
        recipe.setTitle(title);
        recipe.getCategories().addAll(categories);
        recipe.getTags().addAll(tags);
        return recipesRepository.saveAndFlush(recipe);
    }

    private PageRequest firstPage() {
        return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /**
     * The bug this whole query shape exists to avoid.
     * <p>
     * A join onto a to-many collection yields one row per matching category, and a derived COUNT
     * then counts those duplicates - so a single recipe in three categories reports as three
     * results, the page shows it three times, and the last page is wrong. EXISTS keeps one row per
     * recipe.
     */
    @Test
    void countsARecipeOnce_evenWhenItIsInSeveralCategories() {
        CategoryEntity dessert = category("dessert");
        recipe("Tarte aux pommes", List.of(dessert, category("gouter"), category("automne")), List.of());

        Page<String> page = recipesRepository.searchIds(null, dessert.getId(), null, null, firstPage());

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
    }

    @Test
    void returnsEverythingWhenNoFilterIsGiven() {
        recipe("Tarte", List.of(category("dessert")), List.of());
        recipe("Soupe", List.of(category("entree")), List.of());

        Page<String> page = recipesRepository.searchIds(null, null, null, null, firstPage());

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void filtersByCategory() {
        CategoryEntity dessert = category("dessert");
        RecipeEntity tarte = recipe("Tarte", List.of(dessert), List.of());
        recipe("Soupe", List.of(category("entree")), List.of());

        Page<String> page = recipesRepository.searchIds(null, dessert.getId(), null, null, firstPage());

        assertThat(page.getContent()).containsExactly(tarte.getId());
    }

    @Test
    void filtersByTag() {
        TagEntity vegetarien = tag("vegetarien");
        RecipeEntity soupe = recipe("Soupe", List.of(category("entree")), List.of(vegetarien));
        recipe("Steak", List.of(category("plat")), List.of());

        Page<String> page = recipesRepository.searchIds(null, null, vegetarien.getId(), null, firstPage());

        assertThat(page.getContent()).containsExactly(soupe.getId());
    }

    @Test
    void filtersByAuthor() {
        RecipeEntity mine = recipe("Mine", List.of(category("plat")), List.of());
        authenticateAs(otherAuthor);
        recipe("Theirs", List.of(category("plat")), List.of());

        Page<String> page = recipesRepository.searchIds(author.getId(), null, null, null, firstPage());

        assertThat(page.getContent()).containsExactly(mine.getId());
    }

    @Test
    void filtersByTitleFragment_caseInsensitively() {
        RecipeEntity tarte = recipe("Tarte aux POMMES", List.of(category("dessert")), List.of());
        recipe("Soupe a l'oignon", List.of(category("entree")), List.of());

        Page<String> page = recipesRepository.searchIds(null, null, null, "pommes", firstPage());

        assertThat(page.getContent()).containsExactly(tarte.getId());
    }

    @Test
    void combinesEveryFilter() {
        CategoryEntity dessert = category("dessert");
        TagEntity rapide = tag("rapide");
        RecipeEntity wanted = recipe("Tarte rapide", List.of(dessert), List.of(rapide));
        recipe("Tarte lente", List.of(dessert), List.of());
        recipe("Autre rapide", List.of(category("plat")), List.of(rapide));

        Page<String> page = recipesRepository.searchIds(
                author.getId(), dessert.getId(), rapide.getId(), "tarte", firstPage());

        assertThat(page.getContent()).containsExactly(wanted.getId());
    }

    @Test
    void loadsSummariesForAPageOfIds() {
        RecipeEntity first = recipe("First", List.of(category("dessert")), List.of());
        RecipeEntity second = recipe("Second", List.of(category("plat")), List.of());

        List<RecipeEntity> loaded = recipesRepository.findAllForSummary(
                List.of(first.getId(), second.getId()));

        assertThat(loaded).extracting(RecipeEntity::getId)
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        // Fetch-joined, so rendering the author's name costs no extra query per row.
        assertThat(loaded).allSatisfy(recipe ->
                assertThat(recipe.getAuthor().getUsername()).isNotBlank());
    }

    @Test
    void findsTheAuthorIdWithoutLoadingTheRecipe() {
        RecipeEntity recipe = recipe("Tarte", List.of(category("dessert")), List.of());

        assertThat(recipesRepository.findAuthorIdById(recipe.getId())).contains(author.getId());
        // Empty is the 404; a mismatch is the 403.
        assertThat(recipesRepository.findAuthorIdById("no-such-recipe")).isEmpty();
    }

    @Test
    void stampsTheAuthorFromTheSecurityContext() {
        authenticateAs(otherAuthor);

        RecipeEntity recipe = recipe("Theirs", List.of(category("plat")), List.of());

        assertThat(recipe.getAuthor().getId()).isEqualTo(otherAuthor.getId());
        assertThat(recipe.getCreatedAt()).isNotNull();
        // Spring Data stamps both pairs on insert, which is what keeps updated_at NOT NULL safe.
        assertThat(recipe.getUpdatedAt()).isNotNull();
    }

    @Test
    void reportsWhetherACategoryOrTagIsStillUsed() {
        CategoryEntity used = category("dessert");
        CategoryEntity unused = category("boisson");
        TagEntity usedTag = tag("rapide");
        TagEntity unusedTag = tag("noel");
        recipe("Tarte", List.of(used), List.of(usedTag));

        assertThat(recipesRepository.isCategoryUsed(used.getId())).isTrue();
        assertThat(recipesRepository.isCategoryUsed(unused.getId())).isFalse();
        assertThat(recipesRepository.isTagUsed(usedTag.getId())).isTrue();
        assertThat(recipesRepository.isTagUsed(unusedTag.getId())).isFalse();
    }

    @Test
    void reportsWhetherAnIngredientOrUnitIsStillUsed() {
        IngredientEntity farine = ingredient("farine");
        IngredientEntity safran = ingredient("safran");
        UnitEntity gramme = unit("gramme");
        UnitEntity pincee = unit("pincee");

        RecipeEntity recipe = recipe("Tarte", List.of(category("dessert")), List.of());
        RecipeIngredientGroupEntity group = new RecipeIngredientGroupEntity();
        group.setRecipe(recipe);
        group.setPosition(0);
        RecipeIngredientEntity line = new RecipeIngredientEntity();
        line.setGroup(group);
        line.setIngredient(farine);
        line.setUnit(gramme);
        line.setQuantity(new BigDecimal("200.000"));
        line.setPosition(0);
        group.getIngredients().add(line);
        recipe.getIngredientGroups().add(group);
        recipesRepository.saveAndFlush(recipe);

        assertThat(recipesRepository.isIngredientUsed(farine.getId())).isTrue();
        assertThat(recipesRepository.isIngredientUsed(safran.getId())).isFalse();
        assertThat(recipesRepository.isUnitUsed(gramme.getId())).isTrue();
        assertThat(recipesRepository.isUnitUsed(pincee.getId())).isFalse();
    }

    /**
     * The regression a one-step recipe can never catch.
     * <p>
     * Collections are mapped as Set to keep a stray fetch join from producing a bag, and a Set is
     * unordered - Hibernate hydrates a PersistentSet backed by a HashSet and iterates in hash
     * order. Only @OrderBy("position") puts the ORDER BY into the SQL. Without it a five-step
     * recipe stored correctly as 0..4 comes back shuffled.
     */
    @Test
    void readsOrderedChildrenBackInPositionOrder() {
        RecipeEntity recipe = recipe("Tarte", List.of(category("dessert")), List.of());
        List<String> instructions = List.of(
                "Prechauffer le four", "Etaler la pate", "Couper les pommes",
                "Disposer les pommes", "Enfourner 40 min");
        for (int index = 0; index < instructions.size(); index++) {
            RecipeStepEntity step = new RecipeStepEntity();
            step.setRecipe(recipe);
            step.setPosition(index);
            step.setInstruction(instructions.get(index));
            recipe.getSteps().add(step);
        }
        recipesRepository.saveAndFlush(recipe);
        // Drop it from the persistence context so the read really goes back to the database.
        recipesRepository.flush();

        RecipeEntity reloaded = recipesRepository.findById(recipe.getId()).orElseThrow();

        // containsExactly, not containsExactlyInAnyOrder: a shuffle must fail this.
        assertThat(reloaded.getSteps()).extracting(RecipeStepEntity::getInstruction)
                .containsExactlyElementsOf(instructions);
    }
}
