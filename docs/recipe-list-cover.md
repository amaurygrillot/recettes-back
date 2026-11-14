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

`coverVersion` is the id of the stored photo, and clients must treat it as an opaque string. It is read off the list row
(the foreign key), so `GET /lists` pays nothing for it: no statement is added, however many lists there are. A new
photo is a new row with a new id, so every replacement changes it; nothing can change the bytes behind an id.

### The order of the gates

`PUT` answers the first of these that applies, and **each gate is cheaper and says less than the next**:

1. **`401`** — the filter: no token, or a token that is expired, broken or from before a restart.
2. **`413`** — the servlet container, before any code runs: the part is over 8 MB. It is answered even on someone
   else's list, which reveals nothing: the size of *your own* upload says nothing about their list.
3. **`404`** — the list's visibility, `RecipeListService`'s one rule for writes. A list that does not exist and a list that
   is not the caller's answer identically. **This gate is not counted against the rate limit**: a request about a list
   that is not yours must not cost you an upload.
4. **`429`** — the per-account upload limit of `POST /media` (20 per 10 minutes by default), **shared** with it: a list
   photo counts as one upload. Administrators are exempt, exactly as on `POST /media`; the exemption is *not* a
   visibility bypass, so an administrator still gets `404` for another member's list (gate 3 comes first).
5. **`400`** — the format (magic bytes only: JPEG and PNG) and, for the same pipeline, the dimension caps (8000 px per
   side, 40 megapixels, read from the header before any pixel is decoded).
6. **`503`** with `Retry-After` — the decode pool is saturated.

One nuance, kept on purpose: gate 5 is two checks that the shared pipeline makes at two moments. The format is judged
before the pool, and the dimension caps are read by the decoder, which runs *under* the pool's permit. A too-large
image sent while the pool is saturated therefore answers `503`, as it does on `POST /media`. Reordering them would mean
splitting the decoder in two inside the shared pipeline, for a difference nobody can observe except through two
simultaneous failures.

`DELETE` has gates 1 and 3 only. `GET` has 1 and 3, and then the `404`s of [caching](#caching-and-versioning) below.

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
| no `file` part, an empty file, a format that is not JPEG/PNG (**HEIC among them**), a file that cannot be decoded, too many pixels | 400 | `PUT` |
| an unknown `variant`                                                                               | 400    | `GET`            |
| the part is over 8 MB                                                                              | 413    | `PUT`            |
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
   and schedule the old files for deletion **after commit**. A list deleted meanwhile is not found here: `404`, and the
   new files are removed.
4. Both files of the old photo are deleted once the transaction has committed. The answer is the updated list.

**If the attach fails for any reason**, the new photo's two files are deleted (its row was never committed: the failed
transaction rolled it back) and the old photo is untouched, files included — a rollback never runs the
after-commit deletion. A failed `PUT` leaves nothing behind.

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
`MediaStorageService.delete`, which already logs a file it cannot delete — with its storage key, at `error` — and does
not fail the request. There is no second log line and no second delete path.

### Overlapping writes

The lock on the `recipe_lists` row serialises every write to one list's photo. Whatever the interleaving:

| Overlap                                   | Outcome                                                                                          |
|-------------------------------------------|--------------------------------------------------------------------------------------------------|
| two `PUT`s                                | both answer `200`; **the last to commit wins**; the other's photo is deleted, row and files; exactly one row and its two files remain |
| a `PUT` and a `DELETE …/cover`            | both answer `200`; the last to commit decides whether there is a photo; whatever is not the final photo is deleted, row and files |
| a `PUT` and `DELETE /lists/{id}`          | the one that locks second finds either no list (`PUT` → `404`, new files removed) or a list it then deletes with its new photo; nothing is left either way |
| two `DELETE /lists/{id}`                  | one `204`, one `404`                                                                              |

### What can still leave something behind

**Never a row.** The residue is **files only**, and two things can cause it: a file whose deletion failed after commit
(logged by `MediaStorageService.delete` with its storage key, and nothing can find it later), and a process killed
between writing the files and committing the attach, which `POST /media` has always been exposed to for the same
reason (the sweep enumerates rows, so a file with no row is invisible to it). Both are disk space, never a leak: no row
means no route can serve it.

A request that is reading the photo while it is replaced can find its file gone as the response streams. The front
reloads the list and gets the new version.

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
- A photo can be sent as `multipart/form-data` with one part named `file`, 8 MB at most. JPEG and PNG only: HEIC (an
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

## Tests

| What                                                                                                  | Where                                       |
|-------------------------------------------------------------------------------------------------------|---------------------------------------------|
| Every route and **every status** over real HTTP: another member's list (`404` on all three, bytes never served, a matching `ETag` from another member is `404` not `304`), no token and a broken one (`401`), every status inherited from `POST /media` (`400`, `413`, `415`, `429`, `503` through spies of the shared context), an administrator getting `404` for another member's list | `RecipeListCoverIntegrationTest` |
| Cache headers without `v`, with the current `v` and with a stale one — `private` everywhere, never `public`; `ETag` and `304` with the `Cache-Control` repeated; a new version after a replacement | same |
| `GET /media/{id}` answering `404` for a list photo, to its owner and to an administrator              | same                                        |
| A list photo's id refused as a recipe cover, a step picture and an ingredient icon (create and update), `400`, the same answer as an unknown id | same, and `IngredientServiceTest`/`RecipeServiceTest` for the lookups |
| The lifecycle against the real database and a real storage directory under `target/`: replace, remove, delete the list — each time the old row and both files are gone; a failed attach leaves nothing; a rollback leaves the old files | same |
| Two concurrent writes to one list's photo: exactly one row and its two files remain                   | same                                        |
| `findOrphans` does not return an attached photo and does return a detached one; `totalBytesUploadedBy` counts it | `MediaRepositoryTest` (`@DataJpaTest`) |
| `GET /lists` out of N+1 with photos on some of the lists                                              | `RecipeListServiceQueryCountTest`           |
| The `415` for a non-multipart request, on both routes; `413` still answered by its own handler        | `ApiExceptionHandlerTest`, `MediaUploadIntegrationTest` |
| The preflight of the three routes                                                                     | `CorsIntegrationTest`                       |
| YAML and code agree; every operation answers what its `security` promises                             | `OpenApiContractTest`, `OpenApiSecurityConformanceTest` |
| The walkthrough, success and failures                                                                 | `src/test/requests/recipe-lists.http`, `media.http` |
