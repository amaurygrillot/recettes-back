# Category hierarchy — main categories and subcategories

Status: **implemented**. Companion documents:
[recipes-domain-model.md](recipes-domain-model.md) (the rest of the domain),
[api-error-handling.md](api-error-handling.md) (status codes).

## What the feature is

Categories drive the navigation of the recipe library, and a flat list of them stops working as soon as there are more
than a dozen. "Desserts" wants "Tartes", "Glaces" and "Gâteaux" under it; "Tartes" may want "Tartes aux fruits" under
*that*. So categories form a tree: every category is either a **main category** (a root of the navigation) or a
**subcategory** sitting under exactly one other.

Tags deliberately do **not** get this. Tags are open-ended facets — `végétarien`, `rapide`, `Noël` — that a recipe
carries any number of and that have no natural containment. This is now the structural difference between the two,
which settles the open question recorded in
[recipes-domain-model.md](recipes-domain-model.md#categories-and-tags-are-now-structurally-identical--is-that-a-problem):
they are no longer "two lists of labels" that might converge.

## Schema

Two additions, both specified in the review that asked for the feature.

### `categories.category_type`

`MAIN` or `SUB`, stored as a string enum, `NOT NULL`, indexed.

This value is **derivable** — a category is `MAIN` exactly when its relation row points at itself — and storing a
derived value is normally a mistake, so it needs a reason. Two:

- Finding the roots is the first query of every navigation render. `WHERE category_type = 'MAIN'` is an indexable
  predicate; `WHERE category_id = superior_category_id` is a self-comparison no index can serve.
- It makes the invariant readable from plain SQL while debugging, instead of something you reconstruct by joining a
  table to itself.

The duplication is only safe because **one method writes both**: `CategoryService.linkTo` writes the relation row and
the column together, and nothing else touches either. `CategoryServiceTest` asserts they agree.

### `categories_relation`

| Column                 | Notes                                                            |
|------------------------|------------------------------------------------------------------|
| `category_id`          | **primary key**, and a foreign key to `categories`                |
| `superior_category_id` | foreign key to `categories`, `NOT NULL`, separately indexed       |

Exactly one row per category. A subcategory's row points at its **directly** superior category; a main category's row
points at **itself**.

#### Why the roots point at themselves

This is the part that looks wrong at first reading. The alternative is a nullable `superior_category_id`, where a root
simply has no parent.

The self-reference buys one thing: *"every category has exactly one relation row"* becomes a total invariant the
database enforces, with `NOT NULL` doing the work. A category with no row is then unambiguously a bug — a half-finished
write — rather than a legitimate root. With a nullable column those two states are indistinguishable, and the day a
create fails between inserting the category and inserting its edge, the orphan looks exactly like a main category and
nothing ever notices.

The cost is that the self-reference is a lie about the domain: a category is not its own parent. So it is contained.
`CategoryRelationsRepository.superiorIdsByCategoryId()` is the **one** place that translates the stored form into the
domain form, dropping self-edges as it goes, and everything above it — the taxonomy walker, the service, the responses
— sees `null` for a root. Nothing outside that method ever compares an id to its own superior.

#### Why a separate table rather than a column on `categories`

Honestly: because that is what the review specified. A nullable `superior_category_id` column on `categories` would
give the same guarantees (a primary key and a unique constraint are the same guarantee here) with one table fewer.

The one real thing the separate table buys is that an edge can be rewritten without touching the category row an admin
may be concurrently renaming. That is a small benefit at this scale, and it is recorded here rather than argued for, so
that anyone revisiting the shape knows it was a deliberate choice and not an accident.

### What is *not* stored

No closure table, no materialised path, no depth column. Only the direct parent edge.

Ancestors and descendants are derived by loading **every** edge once — two columns, tens of rows — and walking the
adjacency map in memory (`CategoryTaxonomy`). The alternative, one query per level, is an N+1 whose depth is chosen by
the data; the other alternative, a denormalised transitive closure, is a second representation to keep honest on every
write. Categories are a small closed set read on every navigation render and written a handful of times a year, so a
single two-column scan wins on both counts.

**If this table ever grows past a few hundred rows, revisit** — with a PostgreSQL recursive CTE, not with a closure
table.

## The two invariants

### No cycles

Filing a category under one of its own descendants would make every walk below it non-terminating — a hang, not an
error — and would detach the whole subtree from any root. `CategoryService.rejectCycle` refuses it with a **400**.

It walks *down* from the category looking for the proposed parent, rather than *up* from the parent looking for the
category. The second reads more naturally and is wrong: it loops forever on a tree that is *already* cyclic, which is
exactly the state this check exists to keep out and therefore the state it has to survive.

Every read walk also carries a visited set. That is not belt-and-braces for its own sake: a row edited by hand in
`psql` would otherwise turn a public `GET` into a non-terminating recursion, and a hang is a far worse failure than a
truncated branch.

### `category_type` agrees with the relation row

See above. One writer, asserted by a test.

## API

Reads are public — recipes are public, so the taxonomy that navigates them has to be. Writes are admin-only, enforced
by the existing URL rules in `SecurityFilterConfig` (`GET /categories/**` is already `permitAll`, so the three new read
routes needed no security change).

| Method | Path                                    | Auth     | Success | Notes                                             |
|--------|-----------------------------------------|----------|---------|---------------------------------------------------|
| GET    | `/categories`                           | public   | 200     | flat, every level, name-sorted                    |
| GET    | `/categories/hierarchy`                 | public   | 200     | nested forest of main categories                  |
| GET    | `/categories/{id}`                      | public   | 200     | 404 if unknown                                    |
| GET    | `/categories/{id}/subcategories`        | public   | 200     | **all** descendants; 404 if the id is unknown     |
| GET    | `/categories/{id}/superior-categories`  | public   | 200     | ancestor chain, nearest first; `[]` for a root    |
| POST   | `/categories`                           | `ADMIN`  | 201     | 400 on an unknown parent, 409 on a duplicate name |
| PUT    | `/categories/{id}`                      | `ADMIN`  | 200     | 400 on a cycle or an unknown parent               |
| DELETE | `/categories/{id}`                      | `ADMIN`  | 204     | 409 if still used **or** still has children       |

`src/test/requests/categories.http` walks the whole flow, including every failure above.

### Deliberate choices in those routes

**`/subcategories` returns descendants at any depth, not direct children.** The route answers "show me everything filed
under desserts", which is what a listing page wants. A caller that needs one level at a time has `/hierarchy`. The
category itself is not included — it is not below itself, and including it would make an empty result indistinguishable
from a leaf.

**`/superior-categories` is nearest-first.** That is a breadcrumb reversed, and a breadcrumb is what it feeds. A caller
wanting root-first reverses a list it already holds.

**Both answer 404 for an unknown id, not an empty list.** "This category has nothing under it" and "there is no such
category" are different answers, and collapsing them hides a broken link on the client.

**`/hierarchy` nests and carries no `superiorCategoryId`.** Nesting already says who the parent is; a second encoding
of the same fact is a second thing that can disagree. It carries no `categoryType` either — depth 0 is `MAIN` by
construction.

**`PUT` is a full replacement.** An absent `superiorCategoryId` promotes the category to a root rather than leaving its
parent alone. Re-parenting and promoting are both ordinary edits, and a shape where one of them is unexpressible would
force a second endpoint.

**`DELETE` refuses a category that still has children**, with 409. Deleting a node its children point at would fail on
the foreign key as a raw 500 — or, had that constraint been missing, leave a subtree naming a row that no longer
exists: unreachable from any root and invisible to every hierarchy query. Re-parenting or deleting the children is a
decision only an admin can make. A category's own relation row *is* cleared automatically, because for a main category
it points at the row being deleted and nothing else could resolve it.

## Known limitation: filtering recipes by a main category does not include its subcategories

`GET /recipes?categoryId=<desserts>` matches recipes carrying **that exact category**. A recipe filed only under
"Tartes" does not appear.

This is a real gap and it is deferred deliberately rather than overlooked. Expanding the filter to the whole subtree
means the service resolving an id set and the query taking a collection, which changes the shape of the one query in
this codebase that has already shipped a bug (see `RecipesRepository.searchIds` and the `CAST(:q AS string)` note).
Doing it in the same change as the hierarchy itself would have made both harder to review.

Two things make it liveable in the meantime: a recipe may carry several categories, so an author can file a tarte under
both "Desserts" and "Tartes"; and `?categoryName=` matches by name, so a naming convention gets part of the way. **This
is the most obvious next piece of work on categories.**

## Migration note

There is no migration tool (`ddl-auto=update`, no Flyway — see
[recipes-domain-model.md](recipes-domain-model.md#still-open-after-implementation)). Hibernate will add the
`category_type` column and the `categories_relation` table, but it **will not populate them**:

- `category_type` is `NOT NULL` with a default of `MAIN` in the entity, which covers *new* rows only. Existing rows on
  a database that already has categories need `UPDATE categories SET category_type = 'MAIN';` by hand.
- Every existing category needs its self-referencing relation row:
  `INSERT INTO categories_relation (category_id, superior_category_id) SELECT id, id FROM categories;`

Run both once, in that order, against any database that predates this change. A category with no relation row reads as
a root with no children, so the failure is quiet rather than loud — which is exactly why it is written down here.
