# Media storage — user-uploaded images

Status: **design only**, nothing implemented yet. Covers recipe cover pictures, recipe step pictures and ingredient
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

| Column            | Type                | Notes                                                                 |
|-------------------|---------------------|-----------------------------------------------------------------------|
| `id`              | UUID (String)       | what the API exposes; also the cache key                              |
| `storage_key`     | varchar, unique     | relative path under the storage root, e.g. `ab/cd/<uuid>.jpg`         |
| `content_type`    | varchar, not null   | **ours**, decided by the re-encoder — never the client's declared one |
| `width`, `height` | int, not null       | lets the frontend reserve layout space and avoid reflow               |
| `size_bytes`      | bigint, not null    | of the stored (re-encoded) file                                       |
| `checksum_sha256` | char(64), not null  | integrity checking and orphan auditing; **not** unique — see below    |
| `uploaded_by_id`  | FK → `users`        | who to attribute an abuse report to, and the basis for a quota        |
| `created_at`      | timestamp, not null | drives orphan cleanup                                                 |

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
3. **Dimension cap read from the header, before decoding.** Use `ImageIO.getImageReaders` and
   `reader.getWidth(0)` / `getHeight(0)`, which parse the header without allocating the pixel raster. Proposed cap:
   8000 × 8000, and a total-pixel cap. This is what stops a decompression bomb — a 400 KB PNG can declare
   30000 × 30000 and expand to several gigabytes of heap the moment you call `ImageIO.read` on it.
4. **Full decode and re-encode.** Decode, honour the EXIF orientation tag by rotating the raster, then write out a
   fresh JPEG (or PNG when the source has an alpha channel, so ingredient icons keep transparency). Downscale to a
   sane maximum edge (proposed: 2000 px) while re-encoding.
5. **Write under a generated key.** `<uuid>` plus the extension our encoder produced, sharded as
   `<first 2 chars>/<next 2 chars>/<uuid>.<ext>` so no single directory accumulates every image on the server.
6. **Insert the `media` row**, and only then return. If the insert fails, delete the file just written.

### Why re-encoding is the centrepiece

Re-encoding is a single step that neutralises most of the threat model at once, because the bytes that reach disk are
bytes **this application generated**, not bytes a user supplied:

- **Polyglot files** — a valid JPEG whose trailing bytes are also a valid PHP script or HTML document. Re-encoding
  discards everything that is not pixel data.
- **EXIF metadata** — phone photos carry GPS coordinates. Publishing a recipe photo should not publish the
  photographer's home address. Re-encoding drops all metadata; step 4 reads the orientation tag first specifically so
  photos do not come out sideways once it is gone.
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

| Property                       | Meaning                                       | Proposed default        |
|--------------------------------|-----------------------------------------------|-------------------------|
| `app.media.storage-path`       | Root directory for stored bytes               | `./media` (dev)         |
| `app.media.max-file-size`      | Rejection threshold, mirrors the multipart cap| `8MB`                   |
| `app.media.max-dimension`      | Max width/height accepted on upload           | `8000`                  |
| `app.media.max-stored-edge`    | Longest edge after downscaling                | `2000`                  |
| `app.media.jpeg-quality`       | Re-encode quality                             | `0.85`                  |

On the VPS, `app.media.storage-path` must point **outside** the deployment directory, or a redeploy wipes every image.

## Known gaps, deliberately deferred

- **No per-user quota.** Any authenticated user can upload until the disk fills, which on a VPS takes the database
  down with it. Mitigations when it matters: a per-user total-bytes limit returning `413`, plus a disk-space
  monitor. Low risk at friends-and-family scale with named accounts, but it is a real availability hole.
- **No orphan cleanup.** Media uploaded and then never attached (user abandons the form) stays forever. A scheduled
  job deleting rows older than, say, 24 hours with no referencing row is the fix.
- **No rate limiting** on `POST /media` — the same gap already recorded for the password-reset endpoints in
  [password-reset.md](password-reset.md), and worth solving once for the whole API rather than per endpoint.
- **No virus scanning.** Re-encoding makes a stored file very unlikely to be a working payload for anything, and these
  are images served to browsers, not executables handed to users. ClamAV would be the addition if uploads ever open
  beyond known accounts.
