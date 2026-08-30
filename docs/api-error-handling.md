# API error handling — exception mapping and status codes

Status: **design only**, nothing implemented yet. Introduced for the recipes feature; see
[recipes-domain-model.md](recipes-domain-model.md). Distinct from
[security-error-handling.md](security-error-handling.md), which explains why `/error` must be `permitAll()` — that
document covers the servlet-container plumbing, this one covers the application's own exception-to-status mapping.

## The problem this solves

Today every controller method wraps its service call in its own `try/catch` and maps `IllegalStateException → 400`,
`Exception → 500`. That is workable when a request has exactly two outcomes. Recipes have at least five distinct
failure modes:

| Situation                                              | Correct status |
|--------------------------------------------------------|----------------|
| `GET /recipes/{id}` with an id that does not exist      | 404            |
| `POST /recipes` referencing an ingredient id that does not exist | 400   |
| `POST /tags` with a name that already exists            | 409            |
| `PUT /recipes/{id}` on somebody else's recipe           | 403            |
| A blank title                                           | 400            |

All five would arrive at the controller as the same `IllegalStateException`, so the only way to tell them apart would
be to string-match the exception message — which is exactly the "collapsing every error into 400/500" the root
`CLAUDE.md` forbids.

The fix has two halves: **typed exceptions** thrown by services, and **one place** that maps types to statuses.

## Exception hierarchy

In a new `exceptions` package, all extending `RuntimeException` so services stay free of checked-exception noise:

| Exception                     | Status | Thrown when                                                            |
|-------------------------------|--------|------------------------------------------------------------------------|
| `ResourceNotFoundException`   | 404    | the resource named in the **path** does not exist                      |
| `InvalidReferenceException`   | 400    | an id **inside the request body** references something that does not exist |
| `ResourceConflictException`   | 409    | the request conflicts with existing state (duplicate name, in-use delete) |
| `ForbiddenOperationException` | 403    | authenticated, but not allowed — non-author editing a recipe            |

### Why a body reference is 400 and not 404

The root `CLAUDE.md` says 404 for "the referenced resource doesn't exist". That is refined here, because the two cases
behave differently for the caller:

- `GET /recipes/does-not-exist` → **404**. The thing being addressed is absent.
- `POST /recipes` whose body lists `ingredientId: "does-not-exist"` → **400**. `/recipes` exists and is perfectly
  reachable; the *payload* is wrong. Returning 404 here tells the client the endpoint is missing and sends anyone
  debugging it in entirely the wrong direction.

The rule, stated once: **404 describes the request target; a bad id inside the body is invalid input.** The response
detail names the offending field and id so the client can act on it.

422 Unprocessable Content was considered — it is the textbook code for a syntactically valid body that is semantically
wrong — and rejected to keep the status list in `CLAUDE.md` short and unambiguous, since 400 already covers "the input
is wrong" for every client that matters here.

## Two layers of handlers

Per your decision: one global advice for the generic mapping, plus targeted advices where an endpoint family needs
more detail or extra processing.

```
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    ResourceNotFoundException      -> 404
    InvalidReferenceException      -> 400
    ResourceConflictException      -> 409
    ForbiddenOperationException    -> 403
    DataIntegrityViolationException-> 409 only if it is a unique violation, else 500  // see below
    Exception                      -> 500   // logged with the stack trace
}

@RestControllerAdvice(assignableTypes = RecipeController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RecipeExceptionHandler { /* only what needs recipe-specific detail */ }
```

### `DataIntegrityViolationException` must be narrowed before it becomes a 409

The 409 exists for exactly one case: the lost check-then-insert race on a `normalized_name`, where the service's
`existsBy` pre-check passed and the database's unique constraint caught the duplicate anyway. But
`DataIntegrityViolationException` is Spring's wrapper for **every** constraint class — `NOT NULL`, foreign key, check
constraint — and most of those mean an application bug, not a conflict.

Mapping the whole type to 409 fails twice over. A step arriving with a null `instruction` because
`RecipeStepRequest.instruction` was never annotated `@NotBlank` hits `recipe_steps.instruction NOT NULL` and the caller
is told to resolve a conflict that does not exist — a status the root `CLAUDE.md` reserves for "the request conflicts
with existing state". Worse, the failure never reaches the `Exception -> 500` branch, which is the only one that logs a
stack trace, so the actual bug leaves no trace at all.

So the handler inspects the cause and defers anything it does not recognise:

```java

@ExceptionHandler(DataIntegrityViolationException.class)
ResponseEntity<ProblemDetail> handle(DataIntegrityViolationException exception) {
    if (exception.getCause() instanceof ConstraintViolationException violation
            && isUniqueViolation(violation)) {                       // SQLState 23505 on PostgreSQL
        log.info("Unique constraint {} lost a create race", violation.getConstraintName());
        return problem(HttpStatus.CONFLICT, "That name already exists.");
    }
    log.error("Unexpected data integrity violation", exception);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, GENERIC_500_DETAIL);
}
```

`isUniqueViolation` tests the SQLState (`23505`), not the message text — constraint messages are Postgres-version
specific and locale-dependent, and string-matching them is the same anti-pattern this whole document exists to remove.

The narrowing matters beyond tidiness, because two designs elsewhere lean on a real 500 showing up as a 500: the
`media.created_at NOT NULL` trap in [recipes-domain-model.md](recipes-domain-model.md#auditing) and any missed
`@NotBlank` on a child request record both surface as `DataIntegrityViolationException` and would otherwise be
misreported as conflicts.

Controllers then contain no `try/catch` at all:

```java
@PostMapping
public ResponseEntity<RecipeResponse> create(@AuthenticationPrincipal Jwt jwt,
                                             @Valid @RequestBody CreateRecipeRequest request) {
    RecipeResponse created = recipeService.create((String) jwt.getClaims().get("userId"), request);
    return ResponseEntity.created(URI.create("/recipes/" + created.id())).body(created);
}
```

### The ordering rule — this is the trap

Spring resolves an exception by looking first for an `@ExceptionHandler` **on the controller class itself**, then
walking `@ControllerAdvice` beans in `@Order` sequence and taking the **first** one with a matching handler method.
Specificity of the exception type only breaks ties *within* one class — it does **not** make a targeted advice win
over a global one.

So: targeted advices must be `HIGHEST_PRECEDENCE` and the global advice `LOWEST_PRECEDENCE`. Get this backwards and
the targeted handlers become dead code that never runs and that no test will notice unless it asserts on the response
body, not just the status.

A simpler alternative worth knowing about: `@ExceptionHandler` methods declared **directly inside a controller** always
win over any advice, with no ordering to configure. If a per-controller advice class ever turns out to hold a single
handler, that is the lighter form of the same thing.

### Keep targeted advices genuinely specific

A targeted advice should exist because that endpoint family needs something the global one cannot give — a
domain-specific detail message, or extra processing such as logging an audit line. If a targeted handler ends up doing
exactly what the global one does, delete it; a per-controller copy of the generic mapping is the duplication this
whole design is meant to remove.

## Response body: RFC 9457 `ProblemDetail`

Use Spring's built-in `ProblemDetail` rather than a hand-rolled error record. It is a published standard
(`application/problem+json`), it is what `ResponseEntityExceptionHandler` already produces, and it has an extension
mechanism for extra fields — so there is nothing to invent.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No recipe with id 7b1c...",
  "instance": "/recipes/7b1c..."
}
```

Set `spring.mvc.problemdetails.enabled=true` so Spring's own exceptions produce the same shape.

**Detail messages must not leak.** `detail` is written for the caller, and the caller may be anonymous — every
`GET` is public. Never put a stack trace, an SQL fragment, or an internal path in it; the 500 handler logs the
exception and returns a fixed generic detail. This is the same reasoning behind the existing
`server.error.include-message=never` and `include-stacktrace=never` settings. The enumeration-safe wording rules for
the password-reset endpoints in [password-reset.md](password-reset.md) still apply and are not relaxed by having
richer messages elsewhere.

## Interaction with the existing `/error` fix

[security-error-handling.md](security-error-handling.md) documents why `/error` is `permitAll()`: a `@Valid` failure is
reported through the servlet container's `sendError`, which forwards to `GET /error` and re-enters the security filter
chain.

Extending `ResponseEntityExceptionHandler` changes that for handled exceptions: the advice returns a `ResponseEntity`
directly, so `MethodArgumentNotValidException` no longer goes through `sendError` and no longer triggers the forward.
That is a bonus, not the goal.

**The `/error` `permitAll()` rule stays.** It is still the safety net for anything thrown outside the handler chain —
a filter throwing before the dispatcher servlet, for one — and removing it would silently reintroduce the empty-403
bug for those paths. `SecurityErrorHandlingTest` must keep passing unchanged.

## Migrating the existing controllers

`UserController` and `AuthenticationController` move onto the global advice as part of this work, deleting their
duplicated `try/catch` blocks. Two behaviour changes come with that, both corrections:

- `POST /users/create` with an existing username currently returns **400**; it becomes **409**, which is what the root
  `CLAUDE.md` prescribes for a duplicate on create. `UserService.createUser` throws `ResourceConflictException`
  instead of `IllegalStateException`.
- `PUT /users/update` for a missing user becomes **404** rather than 400.

`PUT /users/reinit-password` is **deliberately excluded** from this refinement. Unknown email, unknown token, expired
token and a token issued to another account all keep mapping to the same flat 400 with the same message. That is not
an oversight — it is the anti-enumeration guarantee documented in [password-reset.md](password-reset.md), and it must
survive this refactor. A targeted advice on `UserController` is the right place to enforce it, and the test asserting
it should say why.

`UserControllerTest` and `AuthenticationControllerTest` need their expected statuses updated accordingly.
