package ilenreste.unpeu.recettesback.support;

import ilenreste.unpeu.recettesback.entities.media.MediaEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeCoverPictureEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeEntity;
import ilenreste.unpeu.recettesback.entities.recipes.RecipeListEntity;
import ilenreste.unpeu.recettesback.entities.users.UserEntity;
import ilenreste.unpeu.recettesback.repositories.media.MediaRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListEntriesRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipeListsRepository;
import ilenreste.unpeu.recettesback.repositories.recipes.RecipesRepository;
import ilenreste.unpeu.recettesback.repositories.users.UsersRepository;
import ilenreste.unpeu.recettesback.services.reference.ReferenceNameNormalizer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Rows for the {@code @DataJpaTest}s of the recipe lists: a member, a recipe with covers, a list, an entry.
 * <p>
 * Recipes and media are stamped by auditing ({@code @CreatedBy}), so a test must be signed in as somebody
 * before it creates either — {@link #authenticateAs} is that, and {@link #signOut} undoes it.
 */
public final class RecipeListFixtures {

    public static final String CAKE = "🎂";

    private final UsersRepository usersRepository;
    private final RecipesRepository recipesRepository;
    private final MediaRepository mediaRepository;
    private final RecipeListsRepository listsRepository;
    private final RecipeListEntriesRepository entriesRepository;
    private final ReferenceNameNormalizer normalizer = new ReferenceNameNormalizer();

    public RecipeListFixtures(UsersRepository usersRepository, RecipesRepository recipesRepository,
                              MediaRepository mediaRepository, RecipeListsRepository listsRepository,
                              RecipeListEntriesRepository entriesRepository) {
        this.usersRepository = usersRepository;
        this.recipesRepository = recipesRepository;
        this.mediaRepository = mediaRepository;
        this.listsRepository = listsRepository;
        this.entriesRepository = entriesRepository;
    }

    public UserEntity user(String prefix) {
        UserEntity user = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        user.setUsername(prefix + "-" + suffix);
        user.setEmail(prefix + "-" + suffix + "@example.com");
        user.setPassword("hash");
        user.setEnabled(true);
        return usersRepository.saveAndFlush(user);
    }

    /** Auditing reads the caller from the security context, as it does behind a real request. */
    public void authenticateAs(UserEntity user) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("userId", user.getId())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    public void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** A recipe by whoever is signed in, with no picture. */
    public RecipeEntity recipe(String title) {
        RecipeEntity recipe = new RecipeEntity();
        recipe.setTitle(title);
        return recipesRepository.saveAndFlush(recipe);
    }

    public MediaEntity media() {
        MediaEntity media = new MediaEntity();
        media.setStorageKey("test/" + UUID.randomUUID() + ".jpg");
        media.setThumbnailStorageKey("test/" + UUID.randomUUID() + "-thumb.jpg");
        media.setContentType("image/jpeg");
        media.setWidth(10);
        media.setHeight(10);
        media.setSizeBytes(1);
        media.setChecksumSha256("0".repeat(64));
        return mediaRepository.saveAndFlush(media);
    }

    /** A recipe whose cover pictures are these, in this order: the first is the one a card shows. */
    public RecipeEntity recipeWithCovers(String title, MediaEntity... covers) {
        RecipeEntity recipe = new RecipeEntity();
        recipe.setTitle(title);
        for (int position = 0; position < covers.length; position++) {
            RecipeCoverPictureEntity cover = new RecipeCoverPictureEntity();
            cover.setRecipe(recipe);
            cover.setMedia(covers[position]);
            cover.setPosition(position);
            recipe.getCoverPictures().add(cover);
        }
        return recipesRepository.saveAndFlush(recipe);
    }

    public RecipeListEntity list(UserEntity owner, String name) {
        return list(owner, name, CAKE);
    }

    public RecipeListEntity list(UserEntity owner, String name, String emoji) {
        RecipeListEntity list = new RecipeListEntity();
        list.setOwner(owner);
        list.setName(name);
        list.setNormalizedName(normalizer.normalize(name));
        list.setEmoji(emoji);
        return listsRepository.saveAndFlush(list);
    }

    /** Puts the recipe in the list through the same statement the service uses. */
    public void put(RecipeListEntity list, RecipeEntity recipe, Instant addedAt) {
        entriesRepository.insertIfAbsent(list.getId(), recipe.getId(), addedAt);
    }
}
