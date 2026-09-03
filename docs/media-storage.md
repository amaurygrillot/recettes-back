# Media storage — user-uploaded images

Status: **implemented**. Covers recipe cover pictures, recipe step pictures and ingredient
icons. Part of the recipes design; see [recipes-domain-model.md](recipes-domain-model.md).

## Decision

**Metadata in PostgreSQL, bytes on the VPS filesystem, behind a `MediaStorageService` interface.**

```
media table (metadata only)
  id, storage_key, content_type, width, height,
  size_bytes, checksum_sha256, uploaded_by_id, created_at

bytes -> ${app.media.storage-path}/ab/cd/<uuid>.jpg

MediaStorageService (interface)
  └── FilesystemMediaStorageService     ← now
      (S3MediaStorageService later, with no schema change)
```

The interface plus one implementation mirrors the existing `MailService` / `SmtpMailService` pair, so it is the shape
this codebase already uses for "one capability, one swappable backend".

Package placement for these classes — and whether `services` is grouped by domain at all — is settled in
[recipes-domain-model.md](recipes-domain-model.md#package-layout), which also specifies the `MediaRepository`
queries backing the quota and orphan-cleanup gaps listed at the end of this document.

### Why not the alternatives

- **Bytes in PostgreSQL (`bytea`)** — attractive because one `pg_dump` then restores everything and there are no
  orphaned files. Rejected because image bytes dominate the size of a recipe database within a few dozen recipes:
  dumps get slow, the working set stops fitting in cache, and HTTP range requests and caching all have to be
  reimplemented on top of a `byte[]`. On a VPS with modest disk and RAM, this is the option that degrades first.
- **URL only, no uploads** — smallest design, but it just relocates the problem: you would still need somewhere to
  host the images, and the whole upload story returns later anyway.
- **S3 / MinIO now** — the right end state at scale and the obvious future migration, but it means running and
  maintaining a MinIO container on the VPS from day one for a friends-and-family app. The `MediaStorageService`
  interface is precisely what keeps that a one-class change: `storage_key` is already an opaque string, so it becomes
  an object key with no schema change.

### The cost being accepted

Backups now have two moving parts. `pg_dump` alone no longer restores a working app — the media directory needs its
own backup (rsync/borg/restic to off-site storage), and a restore has to bring back a database and a directory that
agree with each other. Restoring a database newer than the media directory leaves rows pointing at missing files;
`GET /media/{id}` must therefore return **404**, not a 500, when the row exists but the file does not.

## The `media` table

| Column            | Type                | Notes                                                                                                |
|-------------------|---------------------|------------------------------------------------------------------------------------------------------|
| `id`              | UUID (String)       | what the API exposes; also the cache key                                                             |
| `storage_key`     | varchar, unique     | relative path under the storage root, e.g. `ab/cd/<uuid>.jpg`                                        |
| `thumbnail_storage_key` | varchar, unique | key of the list-page variant, e.g. `ab/cd/<uuid>_thumb.jpg` — see below                          |
| `content_type`    | varchar, not null   | **ours**, decided by the re-encoder — never the client's declared one                                |
| `width`, `height` | int, not null       | of the **stored (downscaled)** image — see below; lets the frontend reserve layout space             |
| `size_bytes`      | bigint, not null    | of the stored (re-encoded) file                                                                      |
| `checksum_sha256` | char(64), not null  | of the stored (re-encoded) bytes; integrity checking and orphan auditing; **not** unique — see below |
| `uploaded_by_id`  | FK → `users`        | who to attribute an abuse report to, and the basis for a quota; set by JPA auditing (`@CreatedBy`)   |
| `created_at`      | timestamp, not null | drives orphan cleanup; set by JPA auditing (`@CreatedDate`)                                          |

**Every column in this table describes the file we stored, never the file that was uploaded.** That is obvious for
`content_type` and `size_bytes` and easy to get wrong for `width`/`height`, because the pipeline reads dimensions twice:
once at step 3 from the header, to reject decompression bombs before decoding, and once implicitly at step 4 after
downscaling to `app.media.max-stored-edge`. The step-3 values are already in a local variable when the row is built, so
reusing them is the natural mistake — and it means a 4032×3024 phone photo stored at 2000×1500 records 4032×3024. The
column's whole purpose is defeated: the frontend reserves a box at twice the real pixel size, and every layout computed
from it is wrong for every photo larger than the cap. **Record the dimensions of the `BufferedImage`
that was actually written**, after step 4.

`created_at` and `uploaded_by_id` are populated by Spring Data JPA auditing, which `MediaEntity` opts into itself — it
does not extend `AuditableEntity`, since media rows are immutable and `updated_at` would be dead weight. The mechanics
and the failure mode if the listener is forgotten are in
[recipes-domain-model.md](recipes-domain-model.md#auditing).

### Two stored sizes, not one

**Added during implementation.** The original design stored exactly one image per upload, capped at
2000 px. `RecipeSummaryResponse` carries a cover picture id, so a twenty-recipe list page would then
pull twenty full-size covers — roughly 9 MB of bytes to paint twenty 300 px cards, on every cold
load. That is the single largest performance problem in the media design, and it is invisible until
there are enough recipes for a list page to exist.

So the re-encoder emits **two** files per upload: the full image at `app.media.max-stored-edge`, and
a thumbnail at `app.media.thumbnail-edge` (640 px). `GET /media/{id}` serves the full one;
`GET /media/{id}?variant=thumbnail` serves the small one. Both are immutable per id, so the year-long
cache header applies unchanged to both.

The cost is one extra encode per upload and ~30 KB of disk per image. The saving is roughly 15x on
every list page.

`thumbnail_storage_key` is **stored, not derived** from `storage_key`. Deriving it (append `_thumb`
before the extension) would work today and would impose a naming convention on a column documented as
opaque — the same column that becomes an S3 object key later. Storing it also means a change to the
sharding or naming scheme does not silently break every row written before it.

Neither `width`/`height` nor `size_bytes` describes the thumbnail. They answer "how big is this
image", which is what a frontend reserving layout space needs; the thumbnail's aspect ratio is the
same to within a pixel of rounding.

`checksum_sha256` is not unique, so uploading the same photo twice stores it twice. Deduplicating means two recipes
share one row, and then deleting one recipe must not delete the other's picture — refcounting that is real complexity
for a saving that does not matter at this scale. Recorded for integrity checks only.

`media` rows are referenced by `recipe_cover_pictures.media_id`, `recipe_step_pictures.media_id` and
`ingredients.icon_media_id`. Media is uploaded **first**, independently, then referenced by id when the recipe is
created or updated — the same "reference data must exist first" rule that applies to ingredients.

## Upload — `POST /media`

`multipart/form-data`, one file per request, any authenticated user. Returns `201` with the media id, dimensions and
content type.

The pipeline, in order — each step is a gate, and a failure at any of them stores nothing:

1. **Size cap before anything is read into memory.** `spring.servlet.multipart.max-file-size` and
   `max-request-size` (proposed: 8 MB). Exceeding it is `413 Payload Too Large`.
2. **Format allowlist by magic bytes.** JPEG and PNG only. The declared `Content-Type` header and the filename
   extension are both attacker-controlled and are ignored entirely for this decision; the file's leading bytes decide.
   Anything else is `400`.
3. **Dimension cap read from the header, before decoding.** `ImageIO.getImageReaders` plus
   `reader.getWidth(0)` / `getHeight(0)`, which parse the header without allocating the pixel raster.
   Caps: 8000 px per side **and** 40 megapixels total. This is what stops a decompression bomb — a 400 KB PNG can
   declare 30000 × 30000 and expand to several gigabytes of heap the moment you call `ImageIO.read` on it. These
   dimensions are used for **this gate only** and are then discarded — they are not what goes in the row.

   Both halves of the cap are needed and the second was left as "and a total-pixel cap" with no number: 8000 × 8000
   passes a per-side check and is still 64 MP, which is 256 MB of heap as a raster. 40 MP is comfortably above any
   real camera and an order of magnitude below what would hurt.
4. **Decode at reduced resolution, then re-encode.** Decode, honour the EXIF orientation tag by rotating the raster,
   then write out a fresh JPEG (or PNG when the source has an alpha channel, so ingredient icons keep transparency),
   downscaled to `app.media.max-stored-edge` (2000 px). Note that the orientation rotation can swap width and height,
   which is a second reason the step-3 numbers cannot be reused.

   **Not a "full decode":** the decode uses `ImageReadParam.setSourceSubsampling`, so an 8000 × 6000 photo is decoded
   directly at 2667 × 2000 (~21 MB) rather than materialised at 48 MP (~192 MB) and then shrunk. Nothing above the
   stored-edge cap is ever kept, so there is no reason to allocate it. Without this the dimension cap in step 3 still
   permits a 40 MP raster *per concurrent upload*, which is the DoS the caps were supposed to prevent.

   The downscale halves repeatedly before the final bicubic step. A single large bicubic step aliases badly past
   about 2x, which is very visible at 640 px.
5. **Write under a generated key.** `<uuid>` plus the extension our encoder produced, sharded as
   `<first 2 chars>/<next 2 chars>/<uuid>.<ext>` so no single directory accumulates every image on the server.
6. **Insert the `media` row**, and only then return. Every column is measured from the artefact of steps 4–5:
   `width`/`height` from the written `BufferedImage`, `size_bytes` and `checksum_sha256` from the bytes on disk,
   `content_type` from the encoder we chose. If the insert fails, both files just written are deleted — no
   transaction covers the filesystem, so that unwinding is by hand or not at all.

Two implementation details that are load-bearing rather than incidental:

- **Writes are atomic.** `store` writes to a sibling temp file and `Files.move(..., ATOMIC_MOVE)` it into place, so a
  crash or a full disk halfway through cannot leave a truncated file that later reads happily serve as a valid image.
  A sibling rather than the system temp directory keeps the move on one filesystem, which is what lets it be atomic.
- **Decoding is bounded to a few concurrent operations** (`app.media.max-concurrent-processing`, default 4). Decoding
  is the memory-hungry step and Tomcat will happily run it on all ~200 of its request threads at once; subsampling
  keeps a single decode small, only this keeps a *burst* small. A request that cannot get a permit within
  `app.media.processing-timeout` answers **503 with `Retry-After`** — the honest status, since nothing failed and the
  server is momentarily busy.

### `server.tomcat.max-swallow-size` is part of the size cap, not an unrelated setting

When Tomcat rejects an oversized upload it answers immediately, while the client is still sending. It then has to read
and discard the rest of the body, or it resets the connection — and the client reports a network error instead of the
413 that was actually written. The default swallow limit is 2 MB, *below* our own 8 MB cap, so the common case (a file
only somewhat too big) would always look like a crash.

It is set to 12 MB: enough to cover any upload near the limit, bounded rather than unlimited because swallowing without
limit is itself a way to make the server read arbitrary bytes.

### Why re-encoding is the centrepiece

Re-encoding is a single step that neutralises most of the threat model at once, because the bytes that reach disk are
bytes **this application generated**, not bytes a user supplied:

- **Polyglot files** — a valid JPEG whose trailing bytes are also a valid PHP script or HTML document. Re-encoding
  discards everything that is not pixel data.
- **EXIF metadata** — phone photos carry GPS coordinates. Publishing a recipe photo should not publish the
  photographer's home address. Re-encoding drops all metadata; step 4 reads the orientation tag first specifically so
  photos do not come out sideways once it is gone.

  Reading that tag is the one thing stock `ImageIO` cannot do: it exposes the JPEG APP1/Exif segment as an unparsed
  "unknown" marker segment with no accessor for orientation. Hence the single added dependency,
  `com.drewnoakes:metadata-extractor` — chosen over hand-parsing the TIFF IFD (writing our own binary parser for
  attacker-controlled input is the worse trade in security-sensitive code) and over TwelveMonkeys (which would also
  widen the format allowlist, adding parsers on hostile bytes, which is the opposite of what this document wants).
  Decoding still uses stock `ImageIO` alone.
- **Malformed-image parser exploits** — a corrupt file crafted against a downstream decoder never reaches a viewer,
  because it has to survive our decoder first, in a JVM, server-side.

### Rejected input worth calling out

- **SVG is rejected outright.** SVG is an XML document that can carry `<script>` and external entity references; served
  from your origin it is a stored-XSS primitive, and there is no re-encoding step that makes it safe while it remains
  an SVG. If vector ingredient icons are ever wanted, the answer is a fixed icon set shipped with the frontend, not
  user-uploaded SVG.
- **HEIC is rejected**, because stock `ImageIO` cannot read it. This is a real usability problem, not a theoretical
  one: iPhones shoot HEIC by default, so "add a photo of the dish" fails for a chunk of your actual users. Two ways
  out, both deferred: have the frontend convert to JPEG before upload (browser canvas can do it), or add a decoder
  library server-side. **This one deserves a decision before the feature ships.**
- **WebP and AVIF are rejected** for the same reason — no stock codec. TwelveMonkeys ImageIO adds several of these
  formats as a drop-in `ImageIO` plugin if the format list ever needs widening.

## Download — `GET /media/{id}`

Public, since recipes are public.

- `Content-Type` from the `media` row — our own recorded value, never sniffed from the file at read time.
- `X-Content-Type-Options: nosniff`, so a browser cannot decide the response is HTML and render it in your origin.
- `Content-Disposition: inline; filename="<uuid>.<ext>"` — the generated name, never anything the uploader supplied.
- `Cache-Control: public, max-age=31536000, immutable`. Safe because content is immutable per id: an edited picture is
  a new upload with a new id, never a rewrite of an existing one.
- Path traversal defence in depth: `storage_key` is generated by the server and never parsed from a request, and the
  resolved absolute path is asserted to be inside the configured storage root before the file is opened.
- Row present but file missing → `404`.

**Origin note.** Serving user-uploaded content from the same origin as the API means any file that *did* manage to
render as active content would run with that origin's privileges. `nosniff` plus the re-encode plus the SVG ban closes
that today. Serving media from a separate subdomain (`media.<domain>`) is the standard belt-and-braces version and is
worth doing when the VPS gets its DNS and TLS set up — noted here so it is a deliberate choice rather than an
oversight.

Longer term, nginx can serve the storage directory directly (via `X-Accel-Redirect` if authorisation is ever needed),
taking image bytes off the JVM entirely. Not needed at launch.

## Configuration

| Property                               | Meaning                                            | Default         |
|----------------------------------------|----------------------------------------------------|-----------------|
| `app.media.storage-path`               | Root directory for stored bytes                    | `./media` (dev) |
| `app.media.max-dimension`              | Max width/height accepted on upload                | `8000`          |
| `app.media.max-pixels`                 | Max total pixels accepted on upload                | `40000000`      |
| `app.media.max-stored-edge`            | Longest edge after downscaling                     | `2000`          |
| `app.media.thumbnail-edge`             | Longest edge of the thumbnail variant              | `640`           |
| `app.media.jpeg-quality`               | Re-encode quality                                  | `0.85`          |
| `app.media.max-concurrent-processing`  | Uploads that may decode at once                    | `4`             |
| `app.media.processing-timeout`         | Wait for a decode permit before answering 503      | `10s`           |
| `spring.servlet.multipart.max-file-size` | Hard size cap, enforced by the container         | `8MB`           |
| `server.tomcat.max-swallow-size`       | Body discarded after a rejected upload             | `12MB`          |

The design originally listed an `app.media.max-file-size` "mirroring the multipart cap". It was dropped: the servlet
container rejects an oversized part before a byte reaches application code, so a second copy of that number would be a
value that must agree with another one and eventually will not.

On the VPS, `app.media.storage-path` must point **outside** the deployment directory, or a redeploy wipes every image.

## Known gaps, deliberately deferred

- **No per-user quota.** Any authenticated user can upload until the disk fills, which on a VPS takes the database
  down with it. Low risk at friends-and-family scale with named accounts, but it is a real availability hole.
  `MediaRepository.totalBytesUploadedBy` is written and tested-adjacent but **not called**: enforcing it is a handful
  of lines in `MediaService.upload` returning `413`, plus a disk-space monitor.
- **No orphan cleanup.** Media uploaded and then never attached (user abandons the form) stays forever. A scheduled
  job deleting rows older than, say, 24 hours with no referencing row is the fix.
- **No rate limiting** on `POST /media` — the same gap already recorded for the password-reset endpoints in
  [password-reset.md](password-reset.md), and worth solving once for the whole API rather than per endpoint. Note the
  concurrency bound above is *not* rate limiting: it caps simultaneous work, not requests over time, so one account can
  still upload continuously.
- **No virus scanning.** Re-encoding makes a stored file very unlikely to be a working payload for anything, and these
  are images served to browsers, not executables handed to users. ClamAV would be the addition if uploads ever open
  beyond known accounts.

## A note for whoever runs the tests on Windows

`MediaUploadIntegrationTest` originally sent a PHP webshell string as its "not really an image" payload, and failed with
an unexplained 500. The cause was not the application: Tomcat spools a multipart part to a temp file, and on-access
antivirus quarantined that file mid-request, so `MultipartFile.getBytes()` threw
`FileSystemException: ... contains a virus or potentially unwanted software`.

The HTTP-level test now sends bland text; the polyglot case is covered in `ImageReencoderTest`, which never touches the
disk. Worth knowing before someone spends an afternoon debugging the upload path over it.
