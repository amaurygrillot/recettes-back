# La photo de la liste — the photo of a recipe list

Status: **implemented**. It was written before the code and is updated in place wherever the implementation or the
review proves a decision wrong. Companion documents: [recipe-lists.md](recipe-lists.md) (the lists this extends),
[media-storage.md](media-storage.md) (the pipeline it reuses), [api-error-handling.md](api-error-handling.md) (the
statuses), [service-controller-contract.md](service-controller-contract.md), [openapi.md](openapi.md),
[cors.md](cors.md), [testing-database.md](testing-database.md).

## What it is for

The tile of a list shows a mosaic of the covers of its recipes. That is enough to tell « Desserts » from « À tester »
when the recipes have pictures, and no help at all to tell « Recettes de mamie » from « Pour le dimanche » when they
look alike. A member can therefore give a list **a photo of their own** — the grandmother's cookbook, the family table —
which takes the place of the mosaic on its tile and is shown on the open list. A list without one keeps the mosaic,
exactly as before.

The photo is as private as the list: it is the owner's, nobody else can upload, replace, remove **or read** it, and an
administrator is nobody special here either. That last point is what makes it unlike every other image of the API,
which is public because recipes are.

## The rules (decided)

- A list has **at most one photo**, optional. The owner sets it, replaces it or removes it.
- **Creating or renaming a list never touches the photo.** `POST /lists` makes a list without one and `PUT /lists/{id}`
  leaves whatever is there alone; the photo is given afterwards, by its own routes.
- **Cropping is the front's job.** It fills a square tile and a banner with an automatic centre crop (`object-fit:
  cover`), as it does for recipe thumbnails. The back stores and serves exactly what the media pipeline produces for a
  recipe photo: re-encoded, a full size and a thumbnail. No crop parameter, no focal point.
- **The photo is part of the list's own definition**, like its name and emoji: `updatedAt` changes when it is set,
  replaced or removed. Removing a photo the list does not have changes nothing, `updatedAt` included.
- **No MCP tool.** For the reason given for lists in [recipe-lists.md](recipe-lists.md#mcp): the MCP server is
  administrator-only and a list is private with no admin bypass.

## The contract

`openapi.yaml` was edited first ([openapi.md](openapi.md)). Every route needs a token: the access rule is the one of every
list route, `/lists/**` `authenticated()`, with no role and no `x-required-role`.

| Method | Path                   | Success | What it does                                                                             |
|--------|------------------------|---------|-------------------------------------------------------------------------------------------|
| PUT    | `/lists/{id}/cover`    | 200     | sets or replaces the photo; `multipart/form-data`, one required part `file`; answers the **updated list** |
| DELETE | `/lists/{id}/cover`    | 200     | removes the photo; answers the **updated list**; removing an absent photo is a success that changes nothing |
| GET    | `/lists/{id}/cover`    | 200     | the photo's bytes. `variant=thumbnail` for the small size, `v=` to name the version        |

`PUT` and `DELETE` answer with the whole list, so the front has the new tile data (the version, `updatedAt`) in one call.
`PUT` takes **no media id, ever**: a request carries bytes, never a reference to an existing row (see
[Refusing the id elsewhere](#a-list-photos-media-id-is-refused-everywhere-else) for why that matters).

### The list response

`RecipeListResponse`, on **every** route that returns a list (`GET /lists`, `GET /lists/{id}`, `POST`, `PUT`, and both
cover routes), changes in two ways:

| Field                 | Type                  | Meaning                                                                                         |
|-----------------------|-----------------------|--------------------------------------------------------------------------------------------------|
| `coverVersion`        | string, **`null`** without a photo | opaque; changes whenever the photo changes; the `v` of `GET /lists/{id}/cover`          |
| `recipeCoverMediaIds` | string array          | **renamed from `coverMediaIds`**, meaning unchanged: up to 4 cover pictures of the list's recipes, newest first, for the mosaic. Still computed when the list has a photo, because the front decides what to show |

`RecipeSummaryResponse.coverMediaIds` — the cover pictures of a recipe card in `GET /recipes` — **keeps its name**: it
is another field, and the front already uses it.

**What is always sent.** `RecipeListResponse` declares `required` in the contract: `id`, `name`, `emoji`, `recipeCount`,
`createdAt`, `updatedAt`, `coverVersion` and `recipeCoverMediaIds` are on every response that carries a list, so the types a
client generates from `openapi.yaml` are not all optional. **`coverVersion` is required and nullable**: a list with no photo
sends `"coverVersion": null`, never an absent key, and a generated type reads `coverVersion: string | null`. Only
`containsRecipe` stays optional, because it is sent only with `GET /lists?containing=`. (`GET /lists` is not paged, and a
list entry has no schema of its own, so there is no other list schema to change.)

`coverVersion` is the id of the stored photo, and clients must treat it as an opaque string. It is read off the list row
(the foreign key), so `GET /lists` pays nothing for it: no statement is added, however many lists there are. A new
photo is a new row with a new id, so every replacement changes it; nothing can change the bytes behind an id.

### The order of the gates

`PUT` answers the first of these that applies. **The order is the one the code really has**, which is not quite the
order of the table of statuses below, and the two differences are written here because they were once documented the
other way round. Each gate is cheaper than the next and none of them says anything about a list that is not yours:

1. **`401`** — the filter: no token, or a token that is expired, broken or from before a restart.
2. **`413`** — the servlet container, before any code runs: the request is over 8 MiB (the framing counts). It is answered even on someone
   else's list, which reveals nothing: the size of *your own* upload says nothing about their list.
3. **`415`** — the request is not `multipart/form-data`. **Argument binding, which runs before the controller's body**, so
   before the `404` below: the shape of *your* request says nothing about their list either.
4. **`400`, no `file` part** (or a part under another name) — the same argument binding, therefore also **before the
   `404`**. A request with no file part on a list that is not yours is a `400`, not a `404`.
5. **`404`** — the list's visibility, `RecipeListService`'s one rule for writes. A list that does not exist and a list that
   is not the caller's answer identically. **This gate is not counted against the rate limit**: a request about a list
   that is not yours must not cost you an upload.
6. **`429`** — the per-account upload limit of `POST /media` (20 per 10 minutes by default), **shared** with it: a list
   photo counts as one upload. Administrators are exempt, exactly as on `POST /media`; the exemption is *not* a
   visibility bypass, so an administrator still gets `404` for another member's list (gate 5 comes first).
7. **`400`, the file** — an empty file, and a format that is not JPEG or PNG (magic bytes only). The format is judged
   before the decode pool, from the first bytes.
8. **`503`** with `Retry-After` — the decode pool is saturated.
9. **`400`, the pixels** — the dimension caps (8000 px per side, 40 megapixels, read from the header before any pixel is
   decoded), a file that cannot be decoded and a JPEG that was cut short. **These are read by the decoder, which runs
   *under the pool's permit*, so gate 8 comes first**: a too-large image sent while the pool is saturated answers
   `503`, not `400`, exactly as on `POST /media`.

The second difference is on purpose: reordering 8 and 9 would mean splitting the decoder in two inside the shared
pipeline, for a difference nobody can observe except through two simultaneous failures. The first (3 and 4 before 5) is
Spring's: it is not a choice this code made, and it reveals nothing, so it is documented and left as it is, matching
`POST /media`. Both are asserted by `RecipeListCoverUploadIntegrationTest`.

`DELETE` has gates 1 and 5 only. `GET` has 1 and 5, and then the `404`s of [caching](#caching-and-versioning) below.

### Every status

All of these are `application/problem+json` with `type` **`about:blank`**. The photo adds no typed problem, unlike the
three `409`s of the lists themselves.

| Situation                                                                                         | Status | Route(s)         |
|----------------------------------------------------------------------------------------------------|--------|------------------|
| no token, an expired or broken one                                                                 | 401    | all three        |
| a list that does not exist, or **is not the caller's** (an administrator included)                | 404    | all three        |
| the list has no photo                                                                              | 404    | `GET`            |
| `v` is not the list's current version (a replaced or removed photo)                                | 404    | `GET`            |
| the row exists but its file is gone (a database restored from a newer backup than the media directory) | 404 | `GET`            |
| no `file` part, an empty file, a format that is not JPEG/PNG (**HEIC among them**), a file that cannot be decoded **or was cut short**, too many pixels | 400 | `PUT` |
| an unknown `variant`                                                                               | 400    | `GET`            |
| the request is over 8 MiB, framing included (a file of 8 387 584 bytes or more is not safe)        | 413    | `PUT`            |
| **the request is not `multipart/form-data`**                                                       | **415** | `PUT` (and `POST /media`) |
| the account has used its uploads                                                                   | 429 + `Retry-After` | `PUT` |
| the decode pool is saturated                                                                       | 503 + `Retry-After` | `PUT` |

There is **no `403`** on these routes. `POST /media` has one, for a caller whose token lacks the `USER` role; a list
route asks only for a login, like every other list route, and a member always has one.

#### `415`, and the `500` it fixes

`docs/openapi.md` recorded that a non-multipart `POST /media` answered `500`: Spring raises a `MultipartException`
("Current request is not a multipart request") and nothing mapped it. The new route would have inherited it, so it is
**fixed for both**: `ApiExceptionHandler` answers `415` with a `detail` naming `multipart/form-data`. Only that case:
a `MultipartException` on a request that *is* multipart is a body that could not be parsed or a temporary file that
could not be written, which can be the server's own failure (a full disk, an antivirus), so it stays the `500` it was.
`MaxUploadSizeExceededException`, a subclass, keeps its own `413` handler.

## Caching and versioning

The photo changes (unlike a media id, whose bytes never do), so it cannot be served the way `GET /media/{id}` is: a
year-long `immutable` header on something that can be replaced is unfixable from the server, since there is no request
left to answer. Instead **each version has its own URL**, and the version in the URL is checked on every request.

| Request                                   | Answer                                                                                           |
|--------------------------------------------|--------------------------------------------------------------------------------------------------|
| `GET /lists/{id}/cover` (no `v`)           | `200`, `Cache-Control: private, no-cache`, with its `ETag`: the browser may keep it but must revalidate |
| `GET /lists/{id}/cover?v=<coverVersion>`   | `200`, `Cache-Control: private, max-age=31536000, immutable`                                      |
| `GET /lists/{id}/cover?v=<anything else>`  | **`404`**: that version no longer exists, so the front reloads the list. A stale version is **never** served, under a long cache or any other |

- A blank `v` is no `v`, like every other optional text parameter of the API.
- **`private`, never `public`**, in every case, the `304` included. A shared cache (a proxy, a CDN) must never keep a
  list's photo.
- The `ETag` and every other header are those of `GET /media/{id}`, **built in one place**
  (`controllers/media/MediaResponses`, used by both controllers): `Content-Type` from the row, `Content-Disposition:
  inline` with the generated name, `X-Content-Type-Options: nosniff`, a strong `ETag` per variant
  (`"<sha-256>-full"` or `"-thumbnail"`). `If-None-Match` answers `304`, **and a `304` repeats the `Cache-Control` of the
  `200`** (so `GET /media/{id}`'s `304` now repeats `public, max-age=31536000, immutable` too, which RFC 9111 asks for and
  it did not do).
- **Visibility is evaluated before `If-None-Match`.** The service decides who may see the photo (and the `404`s above)
  and only then does the controller compare the tag. Another member who sends the right `ETag` gets `404`, never
  `304`: a `304` for the wrong person would prove the photo exists and that they hold its bytes.

### The shared device: what a private cache keeps

After sign-out, the browser's private cache still holds the versioned photo. Another account on the same browser
could read it only by knowing its URL, and the API never gives it that URL: the other account's lists are different, and
`coverVersion` is read from the caller's own `GET /lists`. The residue is real and small, and it is the same one that any
site with a private per-user image has on a shared computer.

`Vary: Authorization` was **considered and not chosen**. It would make the cache entry specific to the token that
fetched it, and a token lives about an hour (and dies at every restart): the cache would be useless at every new token,
which is precisely when it matters (the front re-reads the same photos after each login). It would also protect
nothing the URL does not already protect, because a different token for the *same* user is the common case and a
different *user* never has the URL.

## Why the photo is served through its list, not through `/media`

`GET /media/{id}` is public and cached for a year, which is right for pictures of recipes and wrong for this. The photo
is read **only through its list**, so that the single question « may this caller see this list? » decides it. That is
the same `visibleList` rule as `GET /lists/{id}` and the `listId` filter of `GET /recipes`
([recipe-lists.md](recipe-lists.md#who-may-see-a-list--one-place)), and it is what makes the planned public lists free:

> When a list can be made public, `isVisibleTo` gains « or it is public », **and the photo follows with no other
> change**. Writes keep `isOwnedBy`, so a public list's photo is still changed only by its owner.

`GET /media/{id}` answers `404` for a list's photo, **whoever asks, its owner and an administrator included** — with the
text of any unknown id, so the answer cannot be told from « no such media ».

## Where the photo is stored

The bytes go through the existing storage — same files, same two variants, same `MediaStorageService`. What differs is
how a `media` row is marked as a list's, and how that keeps it away from `GET /media/{id}`.

| Option | For | Against |
|---|---|---|
| **1. A nullable `cover_media_id` on `recipe_lists`, pointing at a `media` row** (chosen) | the reference sits on the consumer, like `recipe_cover_pictures.media_id` and `ingredients.icon_media_id`: `media` knows no consumer. `@LastModifiedDate` sees the change by itself. The version is on the row `GET /lists` already reads. The orphan sweep already understands « referenced or not ». One unique foreign key says « at most one photo per list, and a photo belongs to one list » in the schema | « private » is implied by the reference, so every **public** lookup of a `media` row has to exclude the rows a list points at — a probe, in three places, and a fourth in `findOrphans` |
| 2. A column on `media` (`recipe_list_id`, unique, `ON DELETE CASCADE`) | the row says it is private; deleting a list cascades the row | `media` would know about its consumers (the dependency runs the wrong way); `GET /lists` needs a statement more to find each list's photo; `updatedAt` needs touching by hand; the orphan sweep needs a clause anyway, because such a row has no cover, step or icon |
| 3. A dedicated table (`recipe_list_covers`: its own keys, content type, size, checksum…) | **structural privacy**: it is not in `media`, so no public code path can ever reach it, now or later | the nine columns of `media` duplicated (DRY), a second entity and a second way to persist what the pipeline produces, `totalBytesUploadedBy` and the orphan sweep each needing to know a second table; and « the existing media storage » becomes « a copy of it » |
| 4. A **public `POST /media` id** that the list references | no new upload route | the row is public: `GET /media/{id}` serves it to anyone who has the id, with a year-long `public` cache. **Rejected by decision** |
| 5. **Signed URLs** | a link without a token | a signed link works for **anyone who has it** until it expires, and a link is shared, logged and bookmarked. **Rejected** |
| 6. Bytes in `recipe_lists` (`bytea`) | one row, no files | the reasons [media-storage.md](media-storage.md#why-not-the-alternatives) already gives against bytes in PostgreSQL |

Option 1 is chosen for the dependency direction and because the three things the feature needs — the dirty tracking
of `updatedAt`, a version without an extra query, the orphan sweep — all come from the reference being on the list's own
row. Option 3 is the honest runner-up, and the one that answers « what if someone forgets a probe? » with « they
cannot »; it was set aside because it copies a table to avoid three predicates. **The cost of option 1 is paid down
three ways**: the probes live in `MediaRepository` under names that say what they are (`findPublicById`,
`findAllPublicById`, and `findOrphans`), the repository's javadoc says that public code must not use the inherited
`findById`/`findAllById`, and every route that takes a media id has a test that sends a list photo's id.

### Schema

`recipe_lists` gains `cover_media_id`: nullable, a foreign key to `media(id)`, **unique** (so one photo belongs to at
most one list, and its index serves the `NOT EXISTS` probes). `ddl-auto=update` creates it, nullable, on a table that
already has rows: **no SQL script is needed**. The foreign key has no cascade: nothing but this feature deletes a list
photo's row, and it does so explicitly, in the transaction that detaches it.

`RecipeListEntity` becomes `@DynamicUpdate`. Without it Hibernate rewrites **every** column on an `UPDATE`, so a rename
that loaded the list before a photo was attached and flushed after it would write back the old (null) `cover_media_id` — a
silently lost photo, with its row and files left unreferenced. With it, a rename writes `name`, `normalized_name`,
`emoji` and `updated_at`, and a photo write writes `cover_media_id` and `updated_at`, and the two cannot clobber each
other.

### Disk use

`MediaRepository.totalBytesUploadedBy` **counts** list photos: they use disk, `uploaded_by_id` is stamped on the row by
auditing like any other, and the conservative answer for a number whose job is to bound disk is « yes ». A replaced or
removed photo is deleted, so it stops counting. (The quota is still not enforced; see
[media-storage.md](media-storage.md#known-gaps-deliberately-deferred).)

## A list photo's media id is refused everywhere else

Three code paths take a media id from a request, and all three resolve it through a **public** lookup that does not see
a list's photo:

| Where                                              | Lookup                                   | Answer for a list photo's id                               |
|----------------------------------------------------|------------------------------------------|-------------------------------------------------------------|
| `GET /media/{id}` (and `?variant=thumbnail`)       | `MediaRepository.findPublicById`         | `404`, the text of an unknown id                            |
| a recipe's cover pictures and step pictures        | `RecipeService.resolveMedia` → `findAllPublicById` | `400 Unknown coverPictures[].mediaId: <id>` — exactly what an unknown id answers |
| an ingredient's icon (`iconMediaId`)               | `IngredientService.resolveIcon` → `findPublicById`  | `400 Unknown iconMediaId: <id>`                             |

The MCP tools call the same services, so they are covered by the same lookups. In each case the answer **is** the
answer for an id that does not exist, in status, `type` and `detail`, so it never reveals that the id exists.

That, plus **`PUT /lists/{id}/cover` never taking a media id**, is what guarantees a list photo's row is **never
shared**: only this feature references it, once, from one list. Everything that deletes it relies on that — it is safe to
delete the row and its files when a photo is replaced, removed or its list deleted *because* no recipe, step or
ingredient can be pointing at it.

`MediaRepository.findOrphans` gets a fourth `NOT EXISTS` probe for the list's reference (indexed by the unique
constraint). An attached photo is not an orphan; a photo no list points at is one, as any unreferenced row is.

### The rule is a test, not only a javadoc

The privacy of a list's photo rests on one convention: **no code path that starts from a request ever reads a `media`
row by id without the public filter.** Until the audit that followed the merge it was written only in the javadoc of
`MediaRepository`, which a new caller of `findById` has no reason to read. `MediaPrivacyArchitectureTest` fails the build
if any class other than `MediaService` and `RecipeListCoverService`:

- calls **any method of `MediaRepository` but the two public lookups**, `findPublicById` and `findAllPublicById`;
- calls `MediaService.open` (the one method that serves a row without asking who is asking);
- refers to the class object `MediaEntity.class`, which is what `EntityManager.find`, `getReference` and a typed
  `createQuery` need in order to read a row of any id.

Production code only: the tests of the repository read what they like.

**It is an allow-list, and it used to be a deny-list of four methods.** The first version named the three inherited reads
that were thought of (`findById`, `findAllById`, `getReferenceById`), and the review of it found what a deny-list always
misses: `existsById` (the most plausible leak: a validator that accepts a list photo's id as a recipe picture because the
row « exists »), `findAll` in every overload and `count`, the deprecated `getById` and `getOne`, and any query method added
to `MediaRepository` tomorrow. An allow-list closes all of them by construction, because the rule is « these two, and
nothing else » rather than « not these four ». The list is the **real one**: the only calls to `MediaRepository` outside the
two services are `IngredientService.resolveIcon` (`findPublicById`) and `RecipeService.resolveMedia`
(`mediaRepository::findAllPublicById`, a method reference, which ArchUnit reads as a call). `save` and `delete` are called
by `MediaService` alone, and `totalBytesUploadedBy` and `findOrphans` by nobody yet (the quota and the sweep are deferred),
so none of the four is on the list: the day a class that is not one of the two services needs one, the build says so and
the person adding it decides, in one line of the test, that it cannot return a list photo's row. Both are safe by
construction (the first returns a number, the second excludes a list's photo), which is a short conversation.

It is an [ArchUnit](https://www.archunit.org) rule, a **test-only dependency** (`com.tngtech.archunit:archunit`, 1.5.1,
the latest stable). A source scan was the alternative: it needs no dependency, but it can only see the *text* `findById`,
not whose `findById` it is, so it either flags every repository of the application or misses a call through an alias; the
bytecode rule says « this method of this type » exactly. The dependency is a test-scope jar with no transitive weight on
the runtime, which is what makes it acceptable for a rule that guards the privacy of a member's photo.

**`EntityManager` is covered by the class object, not by the call.** The bytecode of `entityManager.find(MediaEntity.class,
id)` says « `EntityManager.find` » and nothing about which entity, and ArchUnit does not see constant arguments, so a rule
on the call would have to forbid every `find` of the application. What it does see is that a class refers to the class
object `MediaEntity.class`, which every such call needs. Nothing in production code refers to it today, so the rule costs
nothing, and it is stricter than asked for: a typed `createQuery("…", MediaEntity.class)` and the `Class` overloads of `Session.get` are caught too.

**What the rule cannot see**, because the bytecode does not say it: a call through a variable typed as the generic
`JpaRepository<MediaEntity, String>` instead of `MediaRepository` (nothing is typed that way), **a method reference to an
inherited method**, such as `mediaRepository::findAllById`, and a `@Query` on *another* repository that selects
`MediaEntity` from a JPQL string. The same family: a **second repository interface** over `MediaEntity` (its calls have
another owner, so it would need its own rule), a query addressed by **string** with no class literal
(`createQuery("… MediaEntity …")` with no result class, `Session.get("MediaEntity", id)`, a native query on `media`, a
`JdbcTemplate`), and **navigation through an association** (`RecipeListEntity.getCover()` from any class that loaded a
list, which is by design: the visibility of the list is the gate, and `GET /lists/{id}/cover` is the only route that
serves what it finds). None of these exists in production code today; each is something a review of a new use of the
media tables has to read. javac compiles the method reference to a handle on the interface that *declares* the
method (`ListCrudRepository`) and keeps the receiver's type only in the call that builds the lambda, so it looks exactly
like the `categoriesRepository::findAllById` that `RecipeService` legitimately passes to a helper. That is a pattern this
code base uses: a new use of `MediaRepository` is something a review has to read. The public lookups are declared on
`MediaRepository` itself, so `mediaRepository::findAllPublicById` is a different method and is the one reference the rule
sees and allows. Moving the repository off `JpaRepository` altogether (so that the inherited methods do not exist on it)
would close the first two gaps by construction, at the price of every test that deletes by id; it was not asked for and is
not done.

**It was proved both ways.** Tests of their own import a class that makes every forbidden access (fifteen on the
repository, among them `existsById`, each `findAll` overload, `count`, `getById`, `getOne`, a reference to a method the
repository declares; plus `open` and the three ways of asking an `EntityManager`) and check that each is reported by
name, so a rule cannot pass by matching nothing; and a second class that does what the code base really does (the two
public lookups, called and referenced) must pass all three, so a rule cannot pass the first by matching everything. And,
locally and never committed, `IngredientService.resolveIcon` was given an `existsById` call before its lookup: the build
failed on `IngredientService.resolveIcon:126` (« calls method `MediaRepository.existsById` »), and passed again when the
line was put back.

## Lifecycle

### Setting or replacing — `PUT /lists/{id}/cover`

**No database connection is held while an image decodes or while the request waits for the decode pool.** The
steps, and which of them is in a transaction:

1. *(short read transaction)* the list's visibility for a write — `404` otherwise. Logged as everywhere in the lists
   (`warn` for a list that exists and is not the caller's).
2. *(no transaction)* the shared pipeline, `MediaService.intake`: the rate limit, the read of the file, the format, the
   decode under the processing pool, the re-encode, and **both files written** under generated keys. It creates **no row**.
3. *(short write transaction)* **attach**: reload the list **with a row lock** (`PESSIMISTIC_WRITE`), read the *current*
   photo, insert the new `media` row (stamped with its uploader by auditing), point the list at it, delete the old row,
   and schedule the old files for deletion **after commit**. A list deleted meanwhile is not found here: `404`, the work
   throws before any commit is attempted, and the new files are removed.
4. Both files of the old photo are deleted once the transaction has committed. The answer is the updated list.

**What happens to the new files depends on whether the commit was ever attempted**, and on nothing else. The files are on
disk before the row exists, and no transaction covers the filesystem, so something has to decide whether they are kept.
« Did the transaction roll back? » is **not** that question, because Spring reports *rolled back* in one case where the
database may in fact have committed (below). The question is whether the work had **returned** when the transaction ended:
if it threw, the commit was never attempted and the rollback is certain; if it returned, whatever failed afterwards failed
*at commit time*, and nobody on this side of the connection can tell whether the row is there.

| How the attach ended | The new files | Why |
|---|---|---|
| **The work threw** (anything thrown inside the attach: the lock, a failed flush, a list deleted meanwhile) and the transaction rolled back | **deleted** | the commit was never attempted, so the row was never committed and nothing can point at them. The old photo is untouched, files included, because a rollback never runs the after-commit deletion. A failed `PUT` leaves nothing behind |
| **Never began** (no connection for the transaction: the pool is exhausted) | **deleted** | nothing was written to the database |
| **Committed**, whatever is thrown after it | **kept** | the list points at them: deleting them is a photo that answers `404` for ever |
| **Reported rolled back after the work had returned**: the **commit itself** failed. Spring says `STATUS_ROLLED_BACK` for a commit that fails with a `DataAccessException` (the connection drops while PostgreSQL commits and the acknowledgement is lost: the driver throws an I/O error, JPA turns it into a `DataAccessException`, and the transaction manager rolls back what it can and says « rolled back »), and for a transaction found rollback-only at commit | **kept**, with a `warn` that names the list and both storage keys | the database may well hold the row, and the report says it does not. A pair of stray files costs disk and is never reachable; a deleted file under a committed row costs the photo |
| **Outcome unknown**: the transaction manager reports `STATUS_UNKNOWN`, which Spring does in two cases only: a commit that fails with a `TransactionException`, and a rollback that itself fails (the connection died mid-attach) | **kept**, with the same `warn` | the same reasoning |

The rule is one sentence: **the new files are deleted when, and only when, the commit was certainly never attempted: the
transaction never began, or the work threw and the transaction rolled back.** It is `MediaService.attachInTransaction`,
which opens the transaction, registers the `afterCompletion` hook first thing inside it, sets a flag once the work has
returned (the hook deletes only on `STATUS_ROLLED_BACK` **with the flag unset**), and has a `catch` that deletes only when
the transaction never began. It refuses to run inside a transaction that is already active (an `IllegalStateException`,
nothing written): the template would join it and the hook would be told how the *outer* transaction ended, after the work
had long returned. `POST /media` goes through the same method ([below](#what-can-still-leave-something-behind)),
which is what keeps the two from disagreeing. The rule used to be « deleted when the transaction rolled back », and before
that one `catch` of every exception around the whole call, which could not tell a rollback from a failure *after* the
commit (the hook that deletes the old files throwing) and deleted the files of a photo that was committed. The request in a
kept case may answer a `500` while the photo is set: **the front re-reads the list after any failure of the `PUT`**, as
after any cancel.

**What the rule costs, written down rather than hidden.** A rollback that is certain but happens after the work returned
(a flush or a `beforeCommit` callback that fails at commit, a transaction found rollback-only) looks exactly like the
lost-acknowledgement case, and so does a work that threw whose rollback then failed (the connection died: the status is
« unknown », and the `warn` says that no commit was attempted). Each keeps its two files too: a stray pair of files that
nothing references and no route can reach. When the server did commit and the answer was an error, the **old** photo's
pair is left behind as well, because its deletion is scheduled after a commit the application never saw succeed; the
`warn` speaks of the new pair only, and the old one is found by the list's former `coverVersion`. The work flushes before it
returns (`listsRepository.flush()` in the attach, for the list; the insert of the row, for `POST /media`) precisely so that
the ordinary failures, a constraint or a lost lock, happen *inside* it and stay certain. What is left is rare and is disk
only: the `warn` carries the list and both keys, so someone cleaning up can tell, by looking up the key, whether a row
exists. Listed under « À valider ».

**Considered, and not chosen: checking the database before deleting.** Asking, in a new transaction, whether a `media` row
has these keys would settle every case, including the ones above. It needs a working database at the very moment the
commit has just failed (the connection that dropped is not the one that asks, but it is the same server), and an answer
given while the first commit is still in flight on a half-dead connection can be wrong. The flag costs no connection and
no judgement call, which is why it is the rule.

The old files are deleted after the commit, and **a failure to delete them never fails the request**: the hook catches
whatever the storage throws, logs one `error` line with the media id and the storage key, and goes on to the second file.
That cannot happen with the filesystem storage (it swallows its own failures), and the day it can (an object store) it
costs a stray file, not a `500` for a photo that was saved.

Why the row is created in step 3 and not in step 2: until the list points at it, a `media` row looks like any other,
public one. `POST /media` has to commit its row before anyone references it, because the caller then references it
from a recipe; this feature has no such second step, so the row is **born attached**, in the same transaction that
points the list at it, and there is no instant at which it exists as an ordinary, publicly readable image.

### Removing — `DELETE /lists/{id}/cover`

Same lock, same transaction: detach, delete the row, delete the files after commit, answer the updated list. A list
with no photo is a success that changes nothing — idempotent, `updatedAt` untouched, no write at all.

### Deleting the list — `DELETE /lists/{id}`

The list is loaded **with the same lock**, its photo's **storage keys are read before** anything is deleted, the list
and then the photo's row are deleted, and the files go after commit. `DELETE /lists/{id}` now takes the lock for every
list, photo or not: it is what makes `PUT … /cover` racing it end in `404` with nothing left, and it replaces the
optimistic-locking catch that used to turn a lost double-click into a `404` (a delete that has to wait for the row lock
finds no row when the first one commits, and that is a `404` too).

Deleting a recipe does not touch any list's photo.

### Files are deleted after the transaction commits

So that a rollback never leaves a row pointing at a deleted file. They are removed through
`MediaStorageService.delete`, which logs a file it cannot delete — with its storage key, at `error`. The hook that calls
it (`MediaService.deleteAfterCommit`, used by the replace, the removal and the deletion of the list alike, so all three
behave the same) also catches anything a storage *throws* instead: it logs it at `error` with the media id and the
exception's one-line description, no stack trace, and still tries the other file. A request that has committed is never
failed by the tidying that follows it, and one file that cannot be removed never keeps the other from being.

### Why the lock comes after the ownership check

`ownedListForUpdate` reads the list **unlocked** and applies the ownership rule, and only then locks the row, with
`EntityManager.refresh(list, PESSIMISTIC_WRITE)`, which re-reads it. Locking first would let anybody who learned a
list's id (it is never shown to anyone but its owner) take the row lock with a request that ends in `404`, and make the
owner's own writes queue behind it. Re-reading is what makes the lock useful: what was read a moment ago may no longer
be what is there (a photo attached meanwhile), and a list deleted while the caller waited is a `404` too. The cost is
one more statement per write, which is not on a hot path. One known edge: a rename that loaded the list before a photo
was attached answers a `coverVersion` read before it, and the next read shows the photo; the photo itself is never
lost (`@DynamicUpdate`).

### Overlapping writes

The lock on the `recipe_lists` row serialises every write to one list's photo. Whatever the interleaving:

| Overlap                                   | Outcome                                                                                          |
|-------------------------------------------|--------------------------------------------------------------------------------------------------|
| two `PUT`s                                | both answer `200`; **the last to commit wins**; the other's photo is deleted, row and files; exactly one row and its two files remain |
| a `PUT` and a `DELETE …/cover`            | both answer `200`; the last to commit decides whether there is a photo; whatever is not the final photo is deleted, row and files |
| a `PUT` and `DELETE /lists/{id}`          | the one that locks second finds either no list (`PUT` → `404`, new files removed) or a list it then deletes with its new photo; nothing is left either way |
| two `DELETE /lists/{id}`                  | one `204`, one `404`                                                                              |

### What can still leave something behind

**Never a row.** The residue is **files only**, and four things can cause it: a file whose deletion failed after commit
(logged with its storage key, and with the media id when the storage threw, and nothing can find it later), a commit that
failed after the work returned, or a commit or a rollback whose outcome is unknown (the new files are kept in both, with a
`warn`: see above), and a process killed between writing the files and committing the attach, which `POST /media` has
always been exposed to for the same reason (the sweep enumerates rows, so a file with no row is invisible to it). All are
disk space, never a leak: no row means no route can serve it.

**`POST /media` follows the same rule, through the same method.** `MediaService.upload` used to commit the row in the
repository's own transaction and delete the files on *any* exception after it, so a commit whose acknowledgement was lost
there deleted the files of a committed, **public** row: an image that answers `404` while a recipe may already reference
it. It now inserts the row in a transaction of its own, through `MediaService.attachInTransaction`, with the row flushed
inside the work: an insert that fails is a certain rollback and deletes the files as before, and a commit that fails keeps
them with a `warn`. A failure of the mapper after the commit (it cannot throw today) keeps them too, which is the right
answer for a row that is there.

A request that is reading the photo while it is replaced can find its file gone as the response streams. The front
reloads the list and gets the new version.

## What the upload form relies on

The front-end design (the « Listes » boards, a countdown button, a cancel while uploading, one message for any `400`)
assumes six things of `PUT /lists/{id}/cover`. They were audited after the feature merged, each one with a test in
`RecipeListCoverUploadIntegrationTest` (real HTTP, the real database, real files) or in the unit test named below. Two
of them were wrong and were fixed; the rest held and are written down here so the front can rely on them.

### 1. `Retry-After`: whole seconds, rounded up, and what values it can carry

| Status | Header value, default properties | Why |
|---|---|---|
| `429` | **1 to 600** | the wait until the caller's *oldest* upload of the rolling window ages out: `upload-limit-window` (10 min) is the most it can be, which is what a member who just spent all 20 uploads in a burst is told (`UploadRateLimiterTest`, and over HTTP with the real limiter in `RecipeListCoverUploadIntegrationTest`); it shrinks as that upload ages, down to 1 |
| `503` | **10, always** | the decode pool is full, so the request waited `app.media.processing-timeout` (10 s) for a permit and is told to come back after that long. There is no other value: the same constant at both places that raise it (`MediaService`) |

- The value is **whole seconds, rounded up, never below 1** (`RetryableException.wholeSeconds`). It used to be truncated,
  so a wait of 59.4 s was sent as `59`: a client that obeyed the header came back 0.4 s early and was refused a second
  time, which the countdown button of the design would have shown as a button that re-enables and fails. The sentence in
  `detail` (« Try again in N seconds ») is computed by the same method and always says the number of the header.
- **The browser can read it.** `Retry-After` is in `Access-Control-Expose-Headers` ([cors.md](cors.md)), and a `429` and
  a `503` of this route carry it from another origin (`RecipeListCoverUploadIntegrationTest` sends the real `Origin` and
  asserts both headers). The front takes N **from the header**, never a constant.
- A `429` can be as long as ten minutes, so « N s » is wrong above a minute: show minutes from 60 s up.
- `Retry-After` is the earliest time a retry can succeed, not a promise: the allowance is the account's, so another tab
  or device that uploads meanwhile spends the slot, and the next answer is a new `429` with a new header.

### 2. Closing the form while the photo is uploading

What the server does depends on **whether the last byte had arrived**, and the front cannot tell from the cancel alone.

| The member cancels… | What happens | Counted against the allowance | The list |
|---|---|---|---|
| **before the whole body arrived** | the container cannot finish reading the multipart body, so the request **never reaches the controller**: no row, no file, no decode permit taken, nothing deleted. Logged at `info`, one line, not as an error. | **No** | untouched |
| **after the whole body arrived** (the server is decoding or attaching) | the server cannot know and does not stop: the photo is decoded, attached and the old one deleted, exactly as if the member had stayed. The answer is written to a closed connection and lost. The decode permit is released as always. | **Yes, once** | **changed** |

- Both cases, for a reset and for an ordinary close of the connection, are tested (hanging up on a raw socket, because
  `fetch` cannot be made to do it halfway). The row, the files, the version, `updatedAt` and the bytes served are the
  same after the abort as before it; the permit count is back to full; a request that follows works.
- **What the front does: read the list again after ANY cancel** (`GET /lists/{id}`, one cheap call) and show what it
  says. The progress shown is not a safe signal of which row of the table applies: upload progress counts the bytes handed to
  the network, is throttled, and a cancel shown at 97 or 99 % can come after the server already has the whole body.
- **The same goes for a client-side timeout.** A saturated decode pool makes the request wait up to 10 s for a permit
  before the `503`, and the decode comes on top. A front timeout (`AbortSignal.timeout`, an XHR `timeout`) shorter than
  about **30 s** is an abort after the body: the photo may be set while the screen says it failed. Do not time out sooner,
  and after any timeout read the list again.
- It used to be logged at `error` with a hundred-line stack trace (`Unhandled exception while serving PUT …`) for every
  cancelled upload, as if the server had failed; `ApiExceptionHandler.handleMultipart` now recognises a hang-up (through
  Spring's own `DisconnectedClientHelper`: the container's abort exceptions, a reset or a broken connection, found in the
  cause chain), answers `400` (read by nobody) and logs one `info` line. A `MultipartException` of a multipart request with
  any other cause (a full disk, an antivirus) stays the `500` it was.
- **A client that goes quiet instead of hanging up** (a phone that lost its signal, a suspended tab) is cut off by the
  server's read timeout (`server.tomcat.connection-timeout`, 60 s by default). This claim was written down as « a `400` logged at
  `info`, like a hang-up » and had never been checked. Checked now, over real HTTP against a second HTTP connector of the
  running server whose read timeout is one second (the shared connector's setting is never touched, so no other test, and no
  pooled connection of the shared client, can be affected by it; the connector is added for the one test and removed after
  it, and it is not a second application context): **it is the container's `408 Request Timeout`**, with Spring Boot's JSON error body (`application/json`, not a
  problem: Tomcat has already ended the request when the exception reaches the handler, so the status and the body are its
  own, and nobody reads them), **and one `info` line from the handler, as for a hang-up**, no `error`, no stack trace. Nothing
  is stored, counted or decoded, and no decode permit is taken. So the log was right and the status was not, and no code needed
  to change: `408` is the status that matches what happened, which is what the status-code rules ask for.

### 3. Every `400`, and the two other bodies

Over HTTP, each of these answers **`400` `application/problem+json`** and nothing else, and leaves the list, the photo and
the disk as they were (`everyFileTheServerCannotUse_is400AsAProblem_…`):

a file whose type and name say JPEG and whose bytes are **HEIC**; an **empty** file; **8001 px** on one side; **41
megapixels**; a **JPEG cut in half**, or lacking only its last two bytes; a **PNG cut in half**; a JPEG signature and
nothing else; an SVG; a GIF; a WebP; and (`aMissingFilePart_…`) a request with **no `file` part**, or a part under
another name. An unknown `variant` on `GET` is a `400` too.

- **A truncated JPEG used to be a `200`.** The JPEG decoder does not fail on a file that stops early: it returns what it
  decoded and fills the rest grey, so a file cut short before it was sent (a partial file on the device, a front that
  sliced it) was stored as a half-grey photo. (A connection that drops *during* the upload is another matter: the request
  fails, see 2.) The decoder says so through a warning (« Truncated File - Missing EOI marker »), and `ImageReencoder` now
  turns that one warning into a `400` whose detail says the image is incomplete or unreadable. Every other warning (a stray byte before a
  marker is routine for real cameras) is still accepted, and a test proves it. The check is on the decoder's own wording,
  so `ImageReencoderTest` fails the day a JDK changes it. A PNG cut short fails to decode on its own and already was a `400`.
  **A possible false positive, accepted:** a JPEG whose scan is complete but which lacks only its two end-marker bytes (some
  old cameras and scanners write them) is refused too. Its `detail` is neutral on purpose (« That image is incomplete or
  unreadable. »): it used to end with « try sending it again », which is the wrong advice for a camera that always
  writes its files that way, and no better for a front that cannot tell the two causes apart.
- **No non-`400` came out of the decoder.** A throwaway mutation run (12 000 JPEGs and PNGs with bytes flipped and files cut
  at random, a fixed seed, not kept in the repository) through `ImageReencoder` produced only `400`s and accepted images:
  no exception other than `InvalidInputException` escaped, so nothing needed catching beyond what was already caught. Take it
  as a spot check, not as a proof.
- **`413`**: `application/problem+json`, `status` 413, a `detail` that says the file is too large — **without naming the limit**
  (the container does not report the one it enforced; the front has its own). The cap is on the **whole request**, the
  multipart framing included (a few hundred bytes with a browser): a file of exactly 8 MiB (8 388 608 bytes) is refused, one a
  kilobyte shorter is judged as a file (a `400` if it is not an image). **The number for the front's own check: refuse a file
  of 8 388 608 − 1024 = 8 387 584 bytes or more, or simply use 8 000 000.** A check of `size <= 8 * 1024 * 1024` lets through
  a band of a few hundred bytes that gets a `413`. A body far beyond the limit (tens of megabytes) can have its connection
  closed by the container before the `413` is read (`server.tomcat.max-swallow-size`, 12 MB,
  [media-storage.md](media-storage.md)); a browser reports that as a network error. This follows from the configuration and is
  not exercised by a test: the front's own check is what keeps a member from ever sending such a file.
- **`415`**: `application/problem+json`, `status` 415, a `detail` naming `multipart/form-data`, for a JSON body and for a
  request with no body and no content type alike.

### 4. A refused upload keeps the photo it was meant to replace

A `PUT` refused with a `400`, a `413`, a `415`, a `429` or a `503` leaves **the photo, its `coverVersion`, the list's
`updatedAt`, the rows, the files and the bytes served** exactly as they were. This holds because the only code that
touches the list runs after every one of those gates and inside the one transaction that either replaces the photo or
rolls back; the new files are written first and are deleted if the transaction never begins or the work throws and it rolls back (the rule of [the outcome of a replace](#setting-or-replacing--put-listsidcover): a refusal never reaches the commit). The tests take a snapshot of all of it
before and after, for each status, with the real limiter and the real decode pool for the last two.

### 5. The order of calls

`POST /lists`, then an immediate `PUT /lists/{id}/cover` on the new list (`200`, a `coverVersion`), then `GET /lists`
(shows that `coverVersion` at once); a later `PUT /lists/{id}` (a rename) answers and keeps the same `coverVersion` and the
photo is still served; `DELETE /lists/{id}/cover` twice is `200` and `200` (the second changes nothing: `coverVersion`
`null`, `updatedAt` as stored); once the list is deleted from elsewhere, `PUT`, `DELETE` and `GET` of its cover are all `404`
and nothing of the photo is left (`theFlowOfTheDesign_…`).

**The precision of `createdAt` and `updatedAt`** is the same in a write's answer and in the next read: **microseconds**,
for the lists and for every other entity of the API. It was not: the answer of a `PUT` or a `DELETE` carried the timestamp
the server had just set, to the nanosecond (`…206783300Z`), while PostgreSQL stores microseconds, so the next `GET`
spelled the same instant `…206783Z` (rounded, not truncated, so it could even be the next microsecond). A client that
compared the strings, or a cache that compared the JSON, saw a change after every refetch. The fix is at the source, in
**one place**: the application's `Clock` (`TimeConfig`) ticks in microseconds, and the auditing that stamps `createdAt` and
`updatedAt` on every entity (`JpaAuditingConfig`) reads that same clock, so no instant the API answers has a finer
fraction than the column that stores it. Nothing is truncated in a response or a mapper. The front can compare the strings
again, though comparing instants is still right.

**One more difference found on the way, and fixed with it:** the answer of `PUT /recipes/{id}` carried the *previous*
`updatedAt`. Auditing stamps it when the update is flushed, which was at commit, after the answer had been built; the
recipe update now flushes before it answers, as the lists already did (`saveAndFlush`). It is not about precision and was
not in the list of eight, but a test that says « the answer of a write equals the next read, for a recipe » cannot pass
without it. (`PasswordResetTokenService` and the JWT claims still call
`Instant.now()`: neither is answered by the API.)

### 6. Sizes

| | stored | shown | verdict |
|---|---|---|---|
| tile, thumbnail | 640 px on the **longest** edge | up to 270 CSS px square, 540 px at 2x, cropped to fill (`object-fit: cover`) | enough at 1x, and at 2x for near-square photos. The crop works on the **short** edge, so only the aspect ratio matters, landscape or portrait alike: at 2x on the largest tile a 4:3 or 3:4 photo (480 px short edge) is enlarged 12 %, a 16:9 one (360 px) 50 %, which is soft. |
| banner, full size | 2000 px on the longest edge | up to 1152 CSS px wide, 2304 px at 2x | **enough** for a landscape photo: it is enlarged 15 % at 2x on a desktop at full width, which is not visible in a photograph. A portrait photo is only 1500 px wide (3:4) and would be enlarged about 54 % at 2x at full width (and is cropped to a wide banner anyway). The server never enlarges, so a smaller photo is kept as it is. |

Left alone, as asked. If a crisp 2x tile on wide photos matters, the lever is `app.media.thumbnail-edge`: 800 would give a
4:3 photo a 600 px short edge, and it applies to **new** uploads only (each row stores its own thumbnail key) and to the recipe
cards that use the same thumbnail. 2000 px for the full size is the cap of every picture of the API; raising it is a decision
about disk and decode memory, not a defect.

## For the front

- The photo is fetched with **`fetch` and an `Authorization` header, then shown from a blob** (`URL.createObjectURL`):
  an `<img src>` cannot carry the token. The URL is
  `/lists/{id}/cover?v={coverVersion}&variant=thumbnail` for a tile and `…?v={coverVersion}` for the banner.
- [cors.md](cors.md) already allows it: `GET`, `PUT` and `DELETE` are allowed methods, `Authorization` and `Content-Type`
  are allowed request headers (the preflight is answered before authentication, so it needs no token), and reading the
  body as a blob needs no exposed header. `ETag` and `Content-Disposition` are **not** exposed, and the front does not
  need them.
- **Leave revalidation to the browser's HTTP cache.** Never set `If-None-Match` (or any other conditional header) from
  the script: the browser then treats the request as uncacheable, bypasses its own cache and hands the script a bare `304`
  with no body. Without `v`, the browser revalidates by itself on every use; with the current `v`, it does not need to.
- A `404` on a `v` means the list changed: reload it with `GET /lists/{id}` and use the new `coverVersion`.
- **After any failure of the `PUT` that is not a `4xx` the front understands (a `500`, a network error, a timeout),
  re-read the list.** A `500` can come after the photo was committed (the old files could not be tidied, the answer could
  not be built), and the server keeps the committed photo's files in that case.
- A photo can be sent as `multipart/form-data` with one part named `file`, under 8 387 584 bytes (see 3 above: the limit is on the whole request). JPEG and PNG only: HEIC (an
  iPhone's default) and WebP are a `400` the front must explain, and converting to JPEG before sending is the answer.

## Decisions I had to make

« À valider » in the pull request repeats this list.

1. **The photo is a `media` row referenced from the list** (option 1), not a column on `media` or a dedicated table.
2. **`coverVersion` is the id of the stored photo**, documented as opaque. A separate token would be one more column for
   no reader's benefit; the id is already unguessable and a new photo always has a new one.
3. **A non-multipart request is a `415` on both routes**, which changes `POST /media` from `500` to `415`.
4. **`GET /media/{id}`'s `304` now repeats `Cache-Control`**, a consequence of building the headers in one place.
5. **`DELETE /lists/{id}` takes the row lock for every list**, which supersedes the optimistic-locking catch (and its two
   tests) that turned a lost double-click into a `404`.
6. **`RecipeListEntity` is `@DynamicUpdate`**, so a rename cannot rewrite the photo.
7. **A list photo counts in `totalBytesUploadedBy`.**
8. **A request whose `file` part is empty or not an image consumes an upload**, as it does on `POST /media`: the permit is
   taken before the bytes are read, so a refusal stays the cheapest thing the request can do.
9. **The dimension caps are read under the decode pool's permit**, as on `POST /media` (see the order of the gates).
10. **A blank `v` is no `v`.**
11. **`Vary: Authorization` is not sent** (see the shared device above).

Added by the audit that followed the merge ([what the upload form relies on](#what-the-upload-form-relies-on)):

12. **`Retry-After` is rounded up**, in the header and in the sentence, and both retryable exceptions share one rounding.
13. **A JPEG cut short is a `400`**, for `POST /media` as well (it is the shared pipeline): it used to be stored with the
    missing part grey.
14. **A client that hangs up mid-upload is a `400` logged at `info`**, not a `500` logged at `error` with a stack trace.

Added by the hardening that followed the review of both pull requests ([the outcome of a replace](#setting-or-replacing--put-listsidcover),
[the guardrail](#the-rule-is-a-test-not-only-a-javadoc)):

15. **The new files are deleted only when the commit was certainly never attempted**: the transaction never began, or the
    work threw and the transaction rolled back. A transaction that reports « rolled back » *after* the work returned (the
    commit itself failed) keeps them, and so does a commit whose outcome is unknown: a stray pair of files is disk, a
    deleted file under a committed row is a lost photo. (The conservative reading, since privacy and data win over
    tidiness.) It used to be « deleted whenever the transaction rolled back », which Spring also says of a commit that
    failed at the database; see [the outcome of a replace](#setting-or-replacing--put-listsidcover).
16. **A failure to delete the old files after the commit is logged, never thrown**, for the replace, the removal and the
    deletion of the list alike.
17. **`createdAt` and `updatedAt` are truncated to microseconds at the source** (the `Clock` and the auditing that reads it),
    for every entity, so that a write's answer and the next read spell the same instant. Truncation, not a response
    mapper: a response that rounds is one more place to forget.
18. **`RecipeListResponse` declares `required`** for every field the server always sends, `coverVersion` among them as
    required and nullable. It is the first response schema of the API to do so; the others are left as they are.
19. **The privacy convention is an ArchUnit rule**, the first test-only dependency of its kind.
20. **The documented order of the gates is the real one** (the shape of the request, `415` and a missing `file` part, is
    answered before the `404`; a saturated pool answers `503` before the `400` of a too-large image). The order itself is
    not changed.
21. **The `detail` of a cut-short JPEG is neutral**: it does not say what to do, because the right advice depends on a
    cause the server cannot see.
22. **A client that goes quiet mid-upload is answered `408` by the container and logged at `info`.** Not changed, and the
    sentence that said `400` is corrected: it was verified over real HTTP, not assumed.
23. **`PUT /recipes/{id}` flushes before it answers**, so that its answer carries the `updatedAt` that was stored. Not one
    of the eight, found by the test that says « a write's answer equals the next read, for a recipe ».

Added by the review of that hardening (the commit-safety change):

24. **`POST /media` follows rule 15 too**, through the one method both use (`MediaService.attachInTransaction`), instead of
    a copy of it. The row is inserted in a transaction of its own and flushed inside the work, so a failed insert is
    still a certain rollback that deletes the files, and a commit that fails keeps them.
25. **The warning on a kept pair of files names the owner (« recipe list <id> » or « a media upload ») and both storage
    keys, not the media id.** The hook runs around the work that creates the row, so it does not know the id; `storage_key`
    is unique, and it is also what someone cleaning up needs in order to find the files. The row, if it is there, is found
    by it.
26. **The guardrail is an allow-list of two methods, not five.** Only `findPublicById` and `findAllPublicById` are called
    outside the two services; `save`, `delete` and `totalBytesUploadedBy` are not, so they are not allowed there. The
    class object `MediaEntity.class` is forbidden outside the two services, which covers `EntityManager.find` and
    `getReference` without having to see their arguments.
27. **The lock probe asks PostgreSQL who blocks whom.** It counts a backend waiting on a lock only if `pg_blocking_pids`
    names the backend of the parked transaction, whose pid the parked step records from inside its own transaction. Any
    other application on the same development database can no longer satisfy it. Proved twice, locally and never
    committed: with the lock taken out of `ownedListForUpdate` all six tests fail (« the second write finished while the
    first held the list's row lock »), and a throwaway test that holds a real row lock in one connection and waits on it in
    another saw the probe count that waiter for the holder's pid and for no other (a different backend, pid 1).
28. **The `408` test gets a connector of its own** instead of lowering the shared one for a second. Not a new application
    context, so not a new connection pool.

## Tests

| What                                                                                                  | Where                                       |
|-------------------------------------------------------------------------------------------------------|---------------------------------------------|
| Every route and **every status** over real HTTP: another member's list (`404` on all three, bytes never served, a matching `ETag` from another member is `404` not `304`), no token and a broken one (`401`), every status inherited from `POST /media` (`400`, `413`, `415`, `429`, `503` through spies of the shared context), an administrator getting `404` for another member's list | `RecipeListCoverIntegrationTest` |
| Cache headers without `v`, with the current `v` and with a stale one — `private` everywhere, never `public`; `ETag` and `304` with the `Cache-Control` repeated; a new version after a replacement | same |
| `GET /media/{id}` answering `404` for a list photo, to its owner and to an administrator              | same                                        |
| A list photo's id refused as a recipe cover, a step picture and an ingredient icon (create and update), `400`, the same answer as an unknown id | same, and `IngredientServiceTest`/`RecipeServiceTest` for the lookups |
| The lifecycle against the real database and a real storage directory under `target/`: replace, remove, delete the list — each time the old row and both files are gone; a failed attach leaves nothing; a rollback leaves the old files | same |
| Overlapping writes, **proving the lock**: the first write is held inside its locked transaction (which records its own backend pid), the second is shown to be *blocked by that backend* (`pg_blocking_pids` in `pg_stat_activity`, so another application on the same database cannot satisfy it), then the first is released. Two `PUT`s, a `PUT` against a `DELETE …/cover`, a `PUT` against `DELETE /lists/{id}` in both orders, two `DELETE /lists/{id}`: the final rows, files and served bytes. Each fails with the lock removed | same |
| The outcome of a replace: a storage that throws after the commit (`200`, the new photo served, its files still on disk, both old deletions attempted, one `error` line with the media id); a rollback (new files gone, old photo untouched); a transaction that never began (new files gone); an exception thrown after a commit that completed (new files kept, photo served); **a real commit that fails at the database after the work returned**, for the list photo and for `POST /media` alike (the backend is terminated just before the commit: the transaction is reported rolled back, and the new files are kept) | same |
| **Rule 15 against Spring's own transaction manager**: a commit that throws a `DataAccessException` after the work returned (Spring reports « rolled back », files kept); a work that throws (files deleted); a transaction that never begins (files deleted, work never run); a commit that throws a `TransactionException` (unknown, files kept); a rollback that fails (unknown, files kept); a commit (files kept). For the list photo's shared method and for `POST /media` alike | `MediaServiceTest`, over `ScriptedTransactionManager`, a real `AbstractPlatformTransactionManager` whose commit, begin and rollback can be made to fail |
| The privacy convention: no class but `MediaService` and `RecipeListCoverService` calls a `MediaRepository` method other than the two public lookups, calls `MediaService.open`, or refers to `MediaEntity.class`; and each of the three rules reports the offender it is meant to | `MediaPrivacyArchitectureTest` (ArchUnit) |
| `createdAt` and `updatedAt` of a write's answer equal those of the next read, for a list and for a recipe | `RecipeListsIntegrationTest`, `RecipesIntegrationTest`, `TimeConfigTest` |
| Every field `RecipeListResponse` declares `required` is on every route that answers a list, `coverVersion` as an explicit `null` without a photo | `RecipeListsIntegrationTest` |
| The order of the gates: a `415` and a missing `file` part before the `404`; a `503` before the `400` of a too-large image | `RecipeListCoverUploadIntegrationTest` |
| A client that goes quiet mid-upload until the server's read timeout: `408`, one `info` line, nothing stored or counted | same (on a second connector of the running server, with a one-second read timeout, added and removed by that one test) |
| `findOrphans` does not return an attached photo and does return a detached one; `totalBytesUploadedBy` counts it | `MediaRepositoryTest` (`@DataJpaTest`) |
| `GET /lists` out of N+1 with photos on some of the lists                                              | `RecipeListServiceQueryCountTest`           |
| The `415` for a non-multipart request, on both routes, and `413` on a part over 8 MB (still answered by its own handler); the `304` of the public `GET /media/{id}` repeating its `Cache-Control` | `RecipeListCoverIntegrationTest`, `ApiExceptionHandlerTest`, `MediaUploadIntegrationTest` |
| The preflight of the three routes                                                                     | `CorsIntegrationTest`                       |
| The six assumptions of the front-end design: the real `429` and `503` and their `Retry-After` readable cross-origin, a hang-up mid-upload and after the body, every unusable file a `400`, a refusal leaving the photo untouched, the order of calls | `RecipeListCoverUploadIntegrationTest` |
| The rounding of a wait, the longest and the shortest a `429` carries, the truncated JPEG, the edge of the per-side cap, the hang-up handler | `RetryableExceptionTest`, `UploadRateLimiterTest`, `ImageReencoderTest`, `ApiExceptionHandlerTest` |
| YAML and code agree; every operation answers what its `security` promises                             | `OpenApiContractTest`, `OpenApiSecurityConformanceTest` |
| The walkthrough, success and failures                                                                 | `src/test/requests/recipe-lists.http`, `media.http` |
