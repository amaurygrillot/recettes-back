# What a controller may assume from a service

Status: **implemented**. Companion document: [api-error-handling.md](api-error-handling.md), which covers how the
exceptions named here become status codes.

This document exists because of a question asked, in one form or another, of eighteen different endpoints during the
review of the recipes PR: *"what if the category could not be created? the response given by the service should be
tested to verify it has really been created."*

The question is the right one. The answer is not "add an `if` after every call" — it is a contract, stated once and
relied on everywhere.

## The contract

Every public service method either **returns a usable value or throws**. There is no third outcome.

Specifically:

- **No method returns `null` to mean failure**, and no method returns a boolean the caller is expected to test.
- **Every failure is a typed exception**, mapped centrally to a status by `ApiExceptionHandler` (and, for the
  security layer, by `ProblemDetailErrorResponder`). The mapping lives in one place; see
  [api-error-handling.md](api-error-handling.md).
- **Every write is transactional**, so a failure part-way through leaves nothing half-written. A create either
  produced a complete row or produced none.
- **A create returns the row as flushed**, not the detached input — so the id is generated, the name is the stored
  (trimmed, normalised) one, and a mapper reading it back sees what the database has.

That is what lets `CategoryController.create` be three lines with no branch in them and still not be optimistic. A
`201` is not "nothing threw"; it is "the service returned a response record, which it can only do after a successful
flush".

### Worked example

```java
@PostMapping
public ResponseEntity<CategoryResponse> create(@Valid @RequestBody CategoryRequest request) {
    CategoryResponse created = categoryService.createCategory(request);
    return ResponseEntity.created(URI.create("/categories/" + created.id())).body(created);
}
```

Everything that can go wrong is already accounted for, and none of it is visible here:

| What goes wrong                      | Where it is caught          | Status |
|--------------------------------------|-----------------------------|--------|
| body fails bean validation           | never reaches the service   | 400    |
| `parentCategoryId` names no row    | `InvalidReferenceException` | 400    |
| the name is already taken            | `ResourceConflictException` | 409    |
| two requests race for the same name  | unique constraint → advice  | 409    |
| the caller is not an admin           | `SecurityFilterConfig`      | 403    |
| the database is down                 | `@ExceptionHandler(Exception)` | 500 |

Adding `if (created == null)` here would be dead code. Adding a `try/catch` would duplicate the advice and, done
inconsistently across eighteen endpoints, would be the thing that eventually reports a 409 as a 500.

## The corollaries the review forced

The contract above was mostly already true. Four places where it was not, and what changed.

### 1. Services return DTOs, never entities

`CategoryController`, `TagController`, `UnitController` and `IngredientController` all used to receive a JPA entity
and map it themselves. That is wrong for three separate reasons, and only the first is about tidiness:

- An entity is a handle on the whole object graph. Whatever the controller maps today, the next person can reach
  `category.getRecipes().get(0).getAuthor().getPassword()` from it.
- `spring.jpa.open-in-view=false` (deliberately — see
  [recipes-domain-model.md](recipes-domain-model.md#reading-recipes-without-n1-queries)) means the transaction is
  **closed** by the time the controller runs. Touching any lazy association there is a `LazyInitializationException`,
  i.e. a 500, and which associations are lazy is not visible from the controller.
- It put the mapper in two layers at once: the controller held a `ReferenceMapper` and so did the service.

Mapping now happens inside the transaction that loaded the row, and controllers hold only their service.

The review's literal suggestion was for `createCategory` to return **the id alone**, since that is the only part the
controller used. Returning the full response record instead keeps the `201` body as the canonical resource — the stored
name is trimmed and the `categoryType` is computed, so it is not an echo of the request — at no extra cost, since the
service had already mapped it. Say the word if the narrower contract is preferred.

### 2. `UserService` writes returned `void`

`POST /users/create` answered `201` with an empty body and no `Location`. There was genuinely nothing for a client to
act on: no id to follow, no way to see the stored username, no way to tell a create that worked from one that did half
its job. Both writes now return a `UserResponse`; the hash and the `enabled` flag never cross.

### 3. A missing `USER` role row failed two statements too late

`UserService.createUser` called `rolesRepository.findByNameEqualsIgnoreCase("USER")` and passed the result straight
into a `UserRolesEntity`. On a database where the role had not been seeded that is `null`, and the failure surfaced as
a `NOT NULL` violation on `user_roles.role_id` — a 500 whose message pointed at the join table rather than at the
missing row, *after* the account had already been inserted.

The lookup now happens **before** the insert and fails with a message naming the role. Same status (this is a server
misconfiguration, not a bad request), but the log says what to fix, and no account is created without its role.

### 4. `POST /recipes` could produce a 500 meaning "you are not signed in"

`RecipeEntity.author` is `@CreatedBy`, stamped by auditing from the security context, and `author_id` is `NOT NULL`. A
request with no `JwtAuthenticationToken` resolves no author, so the insert died on the constraint. The URL rules make
that unreachable today — `POST /recipes` requires `ROLE_USER` — but the guard is one line and the failure it prevents
is an opaque 500 for something with an exact status (403). `RecipeService.create` now checks first.

## What "verify the delete" means here

Deletes return `void`, and that is the right contract, because the interesting outcomes are all exceptions:

```java
public void delete(String id) {
    T entity = findById(id);            // 404 if the id names nothing
    if (isUsed(id)) { throw ... }       // 409, and nothing is removed
    beforeDelete(entity);               // subclass guards, e.g. "still has subcategories" → 409
    repository.delete(entity);
}
```

Note the order. Existence is checked **first**: `deleteById` alone would answer `204` for a row that never existed,
telling the caller their state is in sync with ours when it is not. And every guard runs **before** anything is
removed, rather than being unwound by a rollback afterwards — which is why `beforeDelete` is a hook on the base class
rather than an override of `delete` a subclass could get the order wrong in.

Re-reading the row afterwards to confirm it is gone would add a query and prove nothing the transaction does not
already guarantee.

## Where the tests sit

- **Unit tests** (`CategoryServiceTest`, `IngredientServiceTest`, `UserServiceTest`, `RecipeServiceTest`) assert each
  failure path throws the right type, and that nothing is written when it does.
- **Integration tests** (`ReferenceEndpointsIntegrationTest`, `RecipesIntegrationTest`) assert the status each type
  becomes over real HTTP through the real filter chain — which is the half a `standaloneSetup` MockMvc test cannot
  reach. See [api-error-handling.md](api-error-handling.md).
- **`src/test/requests/*.http`** walks each flow by hand, success case and failure cases side by side, for when the
  question is "what does this actually answer" rather than "does it still pass".
