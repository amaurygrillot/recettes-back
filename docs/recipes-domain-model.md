# Recipes — domain model and API design

Status: **design only**, nothing implemented yet. This document is the specification the implementation session works
from. Companion documents: [media-storage.md](media-storage.md) (images),
[api-error-handling.md](api-error-handling.md) (status codes and exception mapping),
[optional-authentication.md](optional-authentication.md) (how public reads coexist with JWT auth).

## What the feature is

A recipe library for personal / friends-and-family use. Anyone can browse and read recipes without an account;
creating and editing requires a login. Ingredients, categories, tags and units are **shared reference data** stored
once in their own tables, so a recipe references them rather than repeating their text — that is what makes
"all recipes using eggs" or "everything tagged vegetarian" a query instead of a string search.

## Entities

```mermaid
erDiagram
    users ||--o{ recipes : authors
  recipes }o--o{ categories: classified_in
  recipes }o--o{ tags: tagged_with
    recipes ||--o{ recipe_cover_pictures : has
    recipes ||--o{ recipe_steps : has
    recipes ||--o{ recipe_ingredient_groups : has
    recipe_steps ||--o{ recipe_step_pictures : illustrated_by
    recipe_ingredient_groups ||--o{ recipe_ingredients : contains
    ingredients ||--o{ recipe_ingredients : referenced_by
    units ||--o{ recipe_ingredients : measured_in
    media ||--o{ recipe_cover_pictures : stores
    media ||--o{ recipe_step_pictures : stores
    media ||--o| ingredients : icon
```

### Reference tables (shared, unique values)

| Table         | Columns                                                                                         | Who can create         |
|---------------|-------------------------------------------------------------------------------------------------|------------------------|
| `ingredients` | `id`, `name`, `normalized_name` (unique), `icon_media_id` (null), `created_at`, `created_by_id` | any authenticated user |
| `categories`  | `id`, `name`, `normalized_name` (unique)                                                        | `ADMIN` only           |
| `tags`        | `id`, `name`, `normalized_name` (unique)                                                        | `ADMIN` only           |
| `units`       | `id`, `name`, `normalized_name` (unique), `abbreviation`                                        | `ADMIN` only           |

`units` is admin-only by analogy with categories and tags: like them it is a small closed set that shapes how every
recipe reads, and unlike ingredients it is not something a user legitimately needs to extend mid-recipe. **Confirm
this if you disagree** — it is the one permission in this table you did not specify.

#### Why a `normalized_name` column instead of a plain `unique` on `name`

"Unique values" has to mean unique *to a human*, not unique as a byte sequence. Without normalization the ingredients
table happily accepts `Oeuf`, `oeuf`, `Œuf`, ` oeuf `, and `OEUF` as five distinct rows, and the property that
justified having a table at all — one canonical row per real-world ingredient — is dead within a week.

So each reference row stores two values: `name` as the user typed it (used for display), and `normalized_name`
carrying the `unique` constraint. Normalization = trim, collapse inner whitespace, lowercase, strip accents via
`java.text.Normalizer` NFD plus combining-mark removal. This lives in one place (a `ReferenceNameNormalizer` utility)
shared by all four services.

Deliberately **not** normalized away: plurals. `oeuf` and `oeufs` stay two rows. Stemming French correctly is a real
NLP problem, and getting it wrong silently merges unrelated ingredients; a human noticing a duplicate and an admin
merging it is the cheaper failure mode at this scale.

The unique constraint is a real database constraint, not just a service-side `existsBy` check — check-then-insert
races under concurrent requests. The service does check first, so the common case returns a clean 409 with a useful
message, but the constraint is what actually guarantees the invariant. A `DataIntegrityViolationException` escaping
the race also maps to 409, see [api-error-handling.md](api-error-handling.md).

### `recipes`

| Column            | Type                | Notes                                                 |
|-------------------|---------------------|-------------------------------------------------------|
| `id`              | UUID (String)       | `GenerationType.UUID`, matching every existing entity |
| `title`           | varchar, not null   | not unique — see below                                |
| `recommendations` | text, nullable      | free text shown beside the recipe, outside the steps  |
| `author_id`       | FK → `users`        | not null, **immutable after creation**                |
| `created_at`      | timestamp, not null | set by JPA auditing                                   |
| `updated_at`      | timestamp, not null | set by JPA auditing                                   |
| `updated_by_id`   | FK → `users`, null  | set by JPA auditing; null until the first update      |

Categories are **not** a column here — see the next section.

**`author_id` is both the author and the creating user.** You clarified these are the same person, so there is no
separate `created_by` column — a second FK holding the same value forever is a bug waiting to happen (the two drift,
and then nobody knows which one drives permissions). `author_id` is the owner, and it is what the ownership check
reads.

**`title` is not unique.** Two people can legitimately both post "Tarte aux pommes", and one person may keep two
variants. A uniqueness constraint here would produce a 409 the user cannot act on. Recipes are addressed by id.

**`recommendations` is a single text field, not a list.** You described it as a block displayed next to the recipe;
a list would only add ordering machinery for something the frontend renders as one paragraph. If it later needs
bullets, markdown in this field handles it without a schema change.

### Categories are many-to-many

A recipe belongs to **one or more** categories, through a `recipe_categories` join table with a composite primary key
`(recipe_id, category_id)` — structurally identical to `recipe_tags`, and mapped the same way, as a plain
`@ManyToMany`.

| Table               | Columns                                   |
|---------------------|-------------------------------------------|
| `recipe_categories` | `recipe_id`, `category_id` — composite PK |
| `recipe_tags`       | `recipe_id`, `tag_id` — composite PK      |

**At least one category is required.** `CreateRecipeRequest.categoryIds` is `@NotEmpty`; the "one or more" is enforced
at the API layer, not by the schema, since a join table cannot express a minimum cardinality. This keeps the original
intent (every recipe is classified and therefore browsable) while allowing the tarte that is honestly both a dessert and
a goûter.

No ordering column: unlike steps or ingredient lines, there is no meaningful "first" category. The API returns them
sorted by name so the output is at least stable.

#### Categories and tags are now structurally identical — is that a problem?

Worth naming, since after this change both are admin-curated many-to-many reference tables with a unique normalized
name. The only differences left are semantic:

- **Categories** are a taxonomy: a small, stable, mostly-disjoint set (entrées, plats, desserts) that drives the main
  navigation, and every recipe must have at least one.
- **Tags** are open-ended facets (végétarien, sans gluten, rapide, Noël) that can proliferate, and are optional.

That distinction is real and it is a product decision, but it is enforced by convention and by the `@NotEmpty` on
categories — nothing in the schema stops the two from converging into "two lists of labels" over time. Both are kept
because you asked for both tables. **If in six months the category list has grown to twenty entries and recipes carry
five each, that is the signal they have merged in practice and one of them should go.** Recorded here so that is a
recognisable outcome rather than a surprise.

### Ordered child tables

Steps, ingredient groups, ingredient lines and pictures are all ordered, and each carries an explicit `position`
integer with a `UNIQUE (parent_id, position)` constraint.

| Table                      | Columns                                                                                    |
|----------------------------|--------------------------------------------------------------------------------------------|
| `recipe_steps`             | `id`, `recipe_id`, `position`, `instruction` (text, not null)                              |
| `recipe_step_pictures`     | `id`, `step_id`, `media_id`, `position`, `alt_text` (null)                                 |
| `recipe_cover_pictures`    | `id`, `recipe_id`, `media_id`, `position`, `alt_text` (null)                               |
| `recipe_ingredient_groups` | `id`, `recipe_id`, `position`, `title` (nullable)                                          |
| `recipe_ingredients`       | `id`, `group_id`, `ingredient_id`, `quantity`, `unit_id` (null), `note` (null), `position` |

`recipe_ingredient_groups.title` is nullable on purpose: most recipes have a single unnamed ingredient list, and
forcing a title there would make every such recipe carry a meaningless "Ingrédients" heading. Null means "render the
list with no heading". Recipes that do split ("Pour la pâte" / "Pour la garniture") title every group.

`position` is maintained by the service, which renumbers `0..n-1` from the order of the incoming request on every
write. JPA's `@OrderColumn` was considered and rejected: it makes Hibernate issue extra UPDATE statements on
reordering and behaves badly with nulls in the collection. An explicit column the service owns is boring and
predictable, and it is readable from plain SQL when debugging.

### `recipe_ingredients` — quantity and unit

You chose a numeric amount plus a `units` reference table:

- `quantity` — `NUMERIC(10,3)`, **nullable**. Null covers "sel, poivre" and "de l'huile", where no amount is
  meaningful. `BigDecimal` in Java, never `double` — floating-point rounding artifacts have no place in a quantity a
  human typed. Three decimals is enough for 0.5 tsp or 0.25 L without inviting nonsense precision.
- `unit_id` — FK to `units`, **nullable**. Null means a bare count ("3 œufs"). A `PIECE` unit row was considered and
  rejected: it forces every recipe to pick a unit and puts "3 pièces œufs" in front of the renderer.
- `note` — nullable varchar, for the qualifier belonging to *this line* rather than to the ingredient itself:
  "finement haché", "à température ambiante". Without it, people encode that into the ingredient name and the shared
  ingredients table fills up with rows like `oignon finement haché`.

Keeping the amount numeric is what makes serving-scaling and an aggregated shopping list possible later. Neither is in
scope now, but both become schema migrations if the quantity is a string.

## Package layout

You asked for domain grouping given the file count, and it is the right call: this feature adds roughly 40 classes, and
a flat `entities` package holding 16 of them stops being navigable. The layout below uses **the same four domain names
at every layer** — `recipes`, `reference`, `media`, `users` — so the tree can be read either way round: "all the recipe
classes" or "all the repositories".

```
ilenreste.unpeu.recettesback
├── configuration/           unchanged, flat (cross-cutting, not domain-owned)
├── filters/                 unchanged, flat
├── exceptions/              ResourceNotFoundException, InvalidReferenceException,
│                            ResourceConflictException, ForbiddenOperationException
├── entities/
│   ├── AuditableEntity      @MappedSuperclass, shared — stays at the root
│   ├── recipes/             RecipeEntity, RecipeStepEntity, RecipeStepPictureEntity,
│   │                        RecipeCoverPictureEntity, RecipeIngredientGroupEntity,
│   │                        RecipeIngredientEntity
│   ├── reference/           IngredientEntity, CategoryEntity, TagEntity, UnitEntity
│   ├── media/               MediaEntity
│   └── users/               UserEntity, RoleEntity, UserRolesEntity,
│                            PasswordResetTokenEntity            ← existing, moved
├── repositories/
│   ├── recipes/             RecipesRepository
│   ├── reference/           IngredientsRepository, CategoriesRepository,
│   │                        TagsRepository, UnitsRepository
│   ├── media/               MediaRepository
│   └── users/               UsersRepository, RolesRepository, UserRolesRepository,
│                            PasswordResetTokenRepository        ← existing, moved
├── services/                see the note below — this one conflicts with CLAUDE.md
├── controllers/
│   ├── recipes/             RecipeController
│   ├── reference/           IngredientController, CategoryController,
│   │                        TagController, UnitController
│   ├── media/               MediaController
│   └── users/               UserController, AuthenticationController   ← existing, moved
├── mappers/
│   ├── recipes/             RecipeMapper
│   ├── reference/           ReferenceMapper
│   └── media/               MediaMapper
└── models/
    ├── auth/                existing, already domain-grouped
    ├── users/               existing, already domain-grouped
    ├── recipes/             requests/, responses/
    ├── reference/           requests/, responses/
    └── media/               responses/
```

`models` already works this way (`models/auth`, `models/users`), so this generalises a convention the codebase has
rather than inventing one.

`configuration`, `filters` and `exceptions` stay flat. They are cross-cutting: `SecurityFilterConfig` is not a recipes
class or a users class, and `ResourceNotFoundException` is thrown by every domain. Subdividing them would produce
single-class packages that answer no question.

`AuditableEntity` stays at the `entities` root for the same reason — it is the shared supertype, owned by no domain.

### The `services` conflict — needs your call

The root `CLAUDE.md` currently says, verbatim:

> All service classes live directly under `services` — do not create feature-specific packages (e.g. `mail`,
> `notifications`) for them, even for a single interface + implementation pair.

That rule and this request point in opposite directions, and I am not going to quietly override a standing instruction
you wrote. The stakes: recipes add eight services (`RecipeService`, `IngredientService`,
`CategoryService`, `TagService`, `UnitService`, `MediaService`, `MediaStorageService`,
`FilesystemMediaStorageService`) to the six that exist, giving **14 flat files** in one package.

My recommendation is to group them like every other layer and amend `CLAUDE.md`:

```
services/
├── recipes/    RecipeService
├── reference/  IngredientService, CategoryService, TagService, UnitService
├── media/      MediaService, MediaStorageService, FilesystemMediaStorageService
└── users/      UserService, DatabaseUserDetailsService, PasswordResetService,
                PasswordResetTokenService, MailService, SmtpMailService
```

The rule was written when six flat files were perfectly readable and the risk was inventing a `mail` package for two
classes. At 14 files across four unrelated domains that trade-off has inverted — and note the proposed packages are the
*same four domain names* used everywhere else, not per-feature packages invented ad hoc, which is what the rule was
actually guarding against.

**Until you say otherwise, the implementation session should keep `services` flat and follow `CLAUDE.md`.** If you agree
with the recommendation, `CLAUDE.md` line 103 needs updating in the same commit — a design doc must not be the only
place a convention is recorded.

### Moving the existing classes

The `← existing, moved` entries above are a pure package rename of code that already works: no logic changes, only
imports. Worth doing so the tree does not end up half-grouped, but it should be its **own commit**, separate from the
recipes work, so that a `git log` for the feature is not drowned in import churn. It is also the kind of change an IDE
refactor does correctly in one action.

## Repositories

Every query the implementation needs, with the reasoning where the obvious version is wrong. All interfaces extend
`JpaRepository<T, String>`, so `findById`, `findAllById`, `save`, `saveAll`, `delete` and `existsById` come for free and
are not repeated below.

### `RecipesRepository`

#### Listing — `GET /recipes`

The naive version, `Page<RecipeEntity> findByCategories_Id(String id, Pageable p)`, is broken in two ways at once: a
join onto a to-many collection produces **duplicate rows** (one per matching category), and Spring Data's derived
`COUNT` query then counts those duplicates, so `page.getTotalElements()` lies and the last page is wrong.

Filter with `EXISTS` subqueries instead of joins, and page over **ids only**:

```java

@Query("""
        SELECT r.id FROM RecipeEntity r
        WHERE (:authorId   IS NULL OR r.author.id = :authorId)
          AND (:categoryId IS NULL OR EXISTS (SELECT 1 FROM r.categories c WHERE c.id = :categoryId))
          AND (:tagId      IS NULL OR EXISTS (SELECT 1 FROM r.tags       t WHERE t.id = :tagId))
          AND (:q          IS NULL OR LOWER(r.title) LIKE LOWER(CONCAT('%', :q, '%')))
        """)
Page<String> searchIds(@Param("authorId") String authorId,
                       @Param("categoryId") String categoryId,
                       @Param("tagId") String tagId,
                       @Param("q") String q,
                       Pageable pageable);

@Query("SELECT r FROM RecipeEntity r JOIN FETCH r.author WHERE r.id IN :ids")
List<RecipeEntity> findAllForSummary(@Param("ids") Collection<String> ids);
```

`EXISTS` keeps one row per recipe, so both the page and its count are correct. The second query then loads exactly that
page's recipes; their `categories`, `tags` and `coverPictures` collections are filled by Hibernate's batch fetching
(below) in a bounded number of extra queries, not one per row.

**`IN` does not preserve order.** `findAllForSummary` returns rows in whatever order PostgreSQL likes, so the service
must reorder them to match `searchIds`' page content before mapping — otherwise the sort the user asked for silently
disappears. This is the single easiest thing to get wrong in this whole design.

The `:param IS NULL OR ...` form is used instead of a JPA `Specification` because four optional filters is exactly the
size where the Criteria API costs more in ceremony than it returns. If filters keep being added, revisit.

`LIKE '%q%'` cannot use a B-tree index, so title search is a sequential scan. Fine for hundreds of recipes; if it ever
is not, the fix is a `pg_trgm` GIN index or PostgreSQL full-text search, not a different query shape.

#### Detail — `GET /recipes/{id}`

Plain inherited `findById`, plus `spring.jpa.properties.hibernate.default_batch_fetch_size=50` in
`application.properties`. That setting makes Hibernate load each lazy collection level with a single batched
`IN`-query instead of one query per parent, which flattens the N+1 across the whole graph without a single
`@EntityGraph`.

Deliberately not written: a `findDetailById` stacking several `LEFT JOIN FETCH`. More than one collection fetch in one
query throws `MultipleBagFetchException` or returns a cartesian product. Add a targeted `@EntityGraph` later only if the
SQL log shows an actual problem — batch fetching is the version that does not need tuning per query.

#### Ownership check

```java

@Query("SELECT r.author.id FROM RecipeEntity r WHERE r.id = :id")
Optional<String> findAuthorIdById(@Param("id") String id);
```

`PUT` needs the whole entity anyway, but `DELETE` does not — this checks permission and existence in one scalar query
without materialising a recipe that is about to be thrown away. An empty `Optional` is the 404; a mismatch is the 403.

#### Reference-usage checks (the 409-on-delete rule)

All four live here rather than on the reference repositories, because they all ask the same question — "does any recipe
still point at this?" — and keeping them together is what stops a fifth variant being invented later.

```java

@Query("SELECT COUNT(r) > 0 FROM RecipeEntity r JOIN r.categories c WHERE c.id = :categoryId")
boolean isCategoryUsed(@Param("categoryId") String categoryId);

@Query("SELECT COUNT(r) > 0 FROM RecipeEntity r JOIN r.tags t WHERE t.id = :tagId")
boolean isTagUsed(@Param("tagId") String tagId);

@Query("""
        SELECT COUNT(ri) > 0 FROM RecipeEntity r
        JOIN r.ingredientGroups g JOIN g.ingredients ri
        WHERE ri.ingredient.id = :ingredientId
        """)
boolean isIngredientUsed(@Param("ingredientId") String ingredientId);

@Query("""
        SELECT COUNT(ri) > 0 FROM RecipeEntity r
        JOIN r.ingredientGroups g JOIN g.ingredients ri
        WHERE ri.unit.id = :unitId
        """)
boolean isUnitUsed(@Param("unitId") String unitId);
```

Duplicate rows are harmless here — the question is only whether the count exceeds zero.

### Reference repositories

`IngredientsRepository`, `CategoriesRepository`, `TagsRepository` and `UnitsRepository` are the same three methods over
their own entity:

```java
Optional<IngredientEntity> findByNormalizedName(String normalizedName);

boolean existsByNormalizedName(String normalizedName);

Page<IngredientEntity> findByNormalizedNameStartingWithOrderByName(String prefix, Pageable pageable);
```

- `existsByNormalizedName` — the pre-check that produces a clean 409 on create.
- `findByNormalizedName` — lets create return *which* existing row collided, so the client can select it instead of
  retrying blindly.
- `findByNormalizedNameStartingWith` — autocomplete in the recipe editor. **`StartingWith`, not `Containing`**: a
  trailing wildcard uses the index on `normalized_name`, a leading one cannot. Searching against the normalized column
  is also what makes typing `oeuf` find `Œuf`.

Only `IngredientsRepository` needs the paged search (its table is the one that grows); on categories, tags and units a
plain `findAll(Sort)` is enough and the endpoint returns everything.

### Validating body references in one query, not N

`POST /recipes` carries many ids: category ids, tag ids, one ingredient id per line, a unit id per line, media ids. A
`findById().orElseThrow()` per id is 30+ queries on a normal recipe.

The pattern, used identically at all five call sites: collect the distinct ids into a `Set`, call the inherited
`findAllById(ids)` **once**, and compare sizes. If fewer rows came back, subtract the returned ids from the requested
set and throw `InvalidReferenceException` naming the field and the missing ids — which is what makes the 400 response
actionable instead of just "bad request". The loaded entities go into a `Map<String, T>` that the rest of the write path
resolves against, so nothing is fetched twice.

Worth extracting as one small helper (`ReferenceResolver`) rather than written out five times.

### `MediaRepository`

```java

@Query("SELECT COALESCE(SUM(m.sizeBytes), 0) FROM MediaEntity m WHERE m.uploadedBy.id = :userId")
long totalBytesUploadedBy(@Param("userId") String userId);

@Query("""
        SELECT m FROM MediaEntity m
        WHERE m.createdAt < :threshold
          AND NOT EXISTS (SELECT 1 FROM RecipeCoverPictureEntity c WHERE c.media = m)
          AND NOT EXISTS (SELECT 1 FROM RecipeStepPictureEntity  s WHERE s.media = m)
          AND NOT EXISTS (SELECT 1 FROM IngredientEntity         i WHERE i.icon  = m)
        """)
List<MediaEntity> findOrphans(@Param("threshold") Instant threshold);
```

Both back gaps listed as deferred in [media-storage.md](media-storage.md) (per-user quota, orphan cleanup). They are
specified now because both are cheap to add while the entity is being written and awkward to retrofit — but neither
needs a scheduler wired up in this scope.

`COALESCE` matters: `SUM` over zero rows returns `null`, and a `long` return type then throws on unboxing the first time
a user with no uploads is checked.

### Indexes

Beyond the primary keys and the `unique` constraints on `normalized_name`:

| Index                               | Serves                               |
|-------------------------------------|--------------------------------------|
| `recipes(author_id)`                | `authorId` filter, "my recipes"      |
| `recipes(created_at DESC)`          | default listing order                |
| `recipe_categories(category_id)`    | category filter and `isCategoryUsed` |
| `recipe_tags(tag_id)`               | tag filter and `isTagUsed`           |
| `recipe_ingredients(ingredient_id)` | `isIngredientUsed`                   |
| `recipe_ingredients(unit_id)`       | `isUnitUsed`                         |
| `media(created_at)`                 | orphan sweep                         |

The join tables get a PK index on `(recipe_id, category_id)` automatically, which covers lookups *from* a recipe; the
second index above is what covers lookups *from* a category, and a composite PK's index cannot serve that direction.

## Auditing

Enable Spring Data JPA auditing (`@EnableJpaAuditing`) rather than stamping timestamps by hand in every service —
five services each remembering to set `updated_at` is precisely the duplication that gets forgotten in one branch.

- An `AuditableEntity` `@MappedSuperclass` carries `createdAt` (`@CreatedDate`), `updatedAt` (`@LastModifiedDate`)
  and `updatedBy` (`@LastModifiedBy`); recipes and the reference entities extend it.
- An `AuditorAware<UserEntity>` bean reads the `userId` claim from the `JwtAuthenticationToken` in the
  `SecurityContextHolder` and returns `usersRepository.getReferenceById(userId)`. `getReferenceById` returns a lazy
  proxy, so `updated_by_id` stays a genuine foreign key without costing a SELECT on every save.
- It returns `Optional.empty()` when there is no authentication — relevant for seeding and tests; public reads never
  write.

Existing entities (`UserEntity`, `RoleEntity`) are **not** retrofitted onto `AuditableEntity` in this scope. That is a
separate schema change to their tables with no bearing on recipes.

## API surface

`GET` is public everywhere; everything else needs a JWT. See
[optional-authentication.md](optional-authentication.md) for why the JWT filter has to change to make that work.

### Recipes — `/recipes`

| Method | Path            | Auth              | Success | Notes                                                               |
|--------|-----------------|-------------------|---------|---------------------------------------------------------------------|
| GET    | `/recipes`      | public            | 200     | paginated summaries; filters `categoryId`, `tagId`, `authorId`, `q` |
| GET    | `/recipes/{id}` | public            | 200     | full recipe                                                         |
| POST   | `/recipes`      | `USER`            | 201     | `Location` header; body is the created recipe                       |
| PUT    | `/recipes/{id}` | author or `ADMIN` | 200     | partial update, see below                                           |
| DELETE | `/recipes/{id}` | author or `ADMIN` | 204     | **not in your requirements — flagged below**                        |

`categoryId` stays a single-value filter (match recipes carrying *that* category) even though a recipe now has several.
Multi-category filtering — "desserts AND rapide" — needs an AND-vs-OR decision and a different query shape; out of scope
until there is a UI asking for it.

`DELETE` was not in your list. A library with no way to remove a mistaken entry is awkward, and its permission rule is
identical to update, so it is designed here — but say the word and it comes out. If it stays, deletion cascades to
steps, groups, ingredient lines and picture links, and merely dereferences (never deletes) ingredients, tags and
categories.

### Reference data

| Method | Path                | Auth              | Success | Failure notes                                    |
|--------|---------------------|-------------------|---------|--------------------------------------------------|
| GET    | `/ingredients`      | public            | 200     | paginated, `?q=` prefix search for the edit form |
| POST   | `/ingredients`      | any authenticated | 201     | 409 if the normalized name exists                |
| PUT    | `/ingredients/{id}` | `ADMIN`           | 200     | rename / change icon                             |
| DELETE | `/ingredients/{id}` | `ADMIN`           | 204     | **409 if any recipe still references it**        |
| GET    | `/categories`       | public            | 200     |                                                  |
| POST   | `/categories`       | `ADMIN`           | 201     | 409 on duplicate                                 |
| PUT    | `/categories/{id}`  | `ADMIN`           | 200     |                                                  |
| DELETE | `/categories/{id}`  | `ADMIN`           | 204     | 409 if in use                                    |
| GET    | `/tags`             | public            | 200     |                                                  |
| POST   | `/tags`             | `ADMIN`           | 201     | 409 on duplicate                                 |
| PUT    | `/tags/{id}`        | `ADMIN`           | 200     |                                                  |
| DELETE | `/tags/{id}`        | `ADMIN`           | 204     | 409 if in use                                    |
| GET    | `/units`            | public            | 200     |                                                  |
| POST   | `/units`            | `ADMIN`           | 201     | 409 on duplicate                                 |
| PUT    | `/units/{id}`       | `ADMIN`           | 200     |                                                  |
| DELETE | `/units/{id}`       | `ADMIN`           | 204     | 409 if in use                                    |

Note the asymmetry on ingredients: **creation** is open to any authenticated user (your requirement), but **editing
and deleting** are admin-only. Renaming a shared row silently rewrites every recipe referencing it, and deleting one
would orphan them; that is a different kind of power from adding a missing row. Flagged because you specified only the
create side.

Deleting reference data that is still referenced returns **409 Conflict**, never a cascade. Silently gutting other
people's recipes because an admin tidied the tag list is not an acceptable side effect of a DELETE.

### Media — `/media`

| Method | Path          | Auth              | Success | Notes                                  |
|--------|---------------|-------------------|---------|----------------------------------------|
| POST   | `/media`      | any authenticated | 201     | multipart upload, returns the media id |
| GET    | `/media/{id}` | public            | 200     | the image bytes                        |

Fully specified in [media-storage.md](media-storage.md).

## Update semantics

`UpdateRecipeRequest` uses `Optional<T>` fields, matching the existing `UpdateUserRequest` — an absent field is left
alone.

For the **collections** (steps, ingredient groups, cover pictures, **categories**, tags), a present value **replaces the
whole collection**. `Optional<List<RecipeStepRequest>> steps` absent means steps untouched; present means these are now
the steps, in this order. A present `categoryIds` is still validated `@NotEmpty` — an update may not strip a recipe of
its last category.

The alternative — per-element patching with client-supplied child ids and add/update/remove intent — is a much more
complex contract that buys nothing here: the editing UI is a form holding the entire recipe, so it always knows the
full list. Replacement also makes reordering free, where patching needs explicit position juggling.

Implementation note: replacement means `orphanRemoval = true` on the owned `@OneToMany` collections (steps, groups,
pictures), and mutating the existing collection in place (`clear()` then `addAll()`) rather than assigning a new one —
reassigning a Hibernate-managed collection throws. The `@ManyToMany` collections (categories, tags) take the same
in-place treatment but **without** `orphanRemoval`: removing a category from a recipe must delete the join row, never
the category.

## Reading recipes without N+1 queries

Covered per-query in [Repositories](#repositories) above; the settings that make it work:

- `spring.jpa.properties.hibernate.default_batch_fetch_size=50` — batches every lazy collection load.
- Collections mapped as `Set`, not `List`, so a stray fetch join cannot produce a bag.
- `RecipeSummaryResponse` carries only id, title, first cover picture id, categories, tags and author name. A recipe
  list page must not cost one full recipe load per row.
- Pagination is `Pageable` with a **server-side cap** on page size (e.g. 100). Uncapped, `?size=100000` is a free
  denial of service on a VPS.

## Reference data has to be seeded

`POST /recipes` cannot succeed until a category exists, and no category can be created until an `ADMIN` user exists.
This is the same trap that exists today — `CLAUDE.md` notes a `USER` row must be inserted into `roles` by hand before
`POST /users/create` works — except recipes make it four tables deep.

Proposal for this scope: an idempotent `ReferenceDataSeeder` (`ApplicationRunner`) inserting the `USER` and `ADMIN`
roles and a starter set of units (g, kg, ml, l, c. à soupe, c. à café, pincée) when absent. Categories and tags stay
empty — those are product choices, not defaults.

Separately, and **out of scope here**: `ddl-auto=update` with no migration tool is going to hurt on the VPS. It cannot
drop or rename anything, it silently skips changes it cannot make, and nothing records what a given database has had
applied. Adding Flyway deserves its own session before the first real deployment.

## Testing

The 90% line-coverage gate applies to all of this. Concretely:

- Service unit tests with mocked repositories, in the style of the existing `UserServiceTest` — including the failure
  branches, which is where the interesting status codes come from: unknown ingredient id, non-author update attempt,
  duplicate normalized name, deleting an in-use tag, an update with an empty `categoryIds`.
- `RecipeMapper` tested directly against a hand-built entity graph.
- **`@DataJpaTest` for the hand-written `@Query` methods.** A mocked repository proves nothing about JPQL that does not
  compile or a filter that returns the wrong rows, and every query in [Repositories](#repositories) is hand-written.
  Cover at least: each filter of `searchIds` in isolation and combined, the correctness of
  `getTotalElements` with a recipe in several categories (the duplicate-row bug this design avoids), and each of the
  four usage checks returning true and false.
- A `@SpringBootTest` covering the authorization matrix end to end: anonymous GET succeeds, anonymous POST is 401, a
  non-author PUT is 403, an admin PUT succeeds. As the existing `SecurityErrorHandlingTest` shows, a
  `standaloneSetup` MockMvc cannot catch this class of bug because it has no security filter chain.
- `.http` files in `src/test/requests`, one per endpoint/flow, each walking the full scenario including failures:
  `recipes.http`, `ingredients.http`, `categories.http`, `tags.http`, `units.http`, `media.http`.

## Open points to confirm

1. **`services` packaging** — grouping it by domain contradicts `CLAUDE.md` line 103. Recommendation above is to group
   and amend the rule; until you say so, the implementation keeps `services` flat.
2. **Moving existing classes** into the new domain folders — recommended, as its own commit.
3. `DELETE /recipes/{id}` — not in your requirements; designed above unless you cut it.
4. Ingredient **edit/delete** restricted to `ADMIN` while **create** is open to every authenticated user.
5. `units` administered by `ADMIN`, like categories and tags.
6. **Categories vs tags** are now structurally identical; the difference is convention plus the `@NotEmpty`. Kept as two
   tables because you asked for both — flagged so the eventual convergence is recognisable.
7. No `servings` / prep-time / cook-time fields — you did not list them, so they are out. All three are additive
   later, but `servings` in particular is what makes the numeric quantities scalable, so it is worth deciding now
   rather than after recipes exist.
