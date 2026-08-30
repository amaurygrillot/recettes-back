# Optional authentication — making public reads work

Status: **design only**, nothing implemented yet. Prerequisite for the recipes feature, which is the first thing in
this app with endpoints that are readable by anyone and writable only by an authenticated user. See
[recipes-domain-model.md](recipes-domain-model.md).

## Why the current filter cannot express "public read, authenticated write"

`JwtAuthenticationFilter` today is all-or-nothing. For any request whose path is not in its hardcoded
`PUBLIC_ENDPOINTS` list, a missing `Authorization` header is an immediate `401` written by the filter, and the request
never reaches `authorizeHttpRequests`:

```java
if (authHeader == null || !authHeader.startsWith("Bearer ")) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    return;
}
```

That works while every route is either fully public or fully protected. `GET /recipes` is neither: it must succeed
without a token *and* still know who the caller is when a token is present.

Adding the recipe reads to `PUBLIC_ENDPOINTS` does not solve it — it makes it worse. `shouldNotFilter` returning true
means the filter is skipped **entirely**, so an authenticated `GET /recipes` carrying a perfectly valid token gets no
`Authentication` in the `SecurityContext` either. The endpoint becomes blind to identity for everyone, which forecloses
anything personalised later (favourites, "my recipes", drafts) and, more immediately, means the `AuditorAware` bean
cannot see the current user on any mixed-access route.

There is also the duplication the root `CLAUDE.md` already flags: `PUBLIC_ENDPOINTS` and the `authorizeHttpRequests`
rules are two hand-maintained lists of the same thing, and every new public route has to be added to both or it breaks
in a confusing way. Recipes would add six or more entries to each.

## The change

Make the filter **authenticate when it can, and decline to decide when it cannot**.

| Request                        | Today                     | After                                                 |
|--------------------------------|---------------------------|-------------------------------------------------------|
| No `Authorization` header      | 401 from the filter       | continue the chain unauthenticated; the chain decides |
| `Bearer <valid token>`         | authenticated             | authenticated (unchanged)                             |
| `Bearer <invalid/expired>`     | 401 from the filter       | 401 from the filter (unchanged)                       |
| Malformed header               | 401 from the filter       | continue unauthenticated                              |

A **present but invalid** token still fails fast with a 401. That distinction is deliberate: no credentials is a
legitimate way to call a public endpoint, but a broken token is a caller who thinks they are authenticated and is not,
and silently downgrading them to anonymous turns an expired session into a confusing 403 on the next write.

With that in place:

- `shouldNotFilter` and `PUBLIC_ENDPOINTS` are **deleted**. The filter runs on every request and only ever *adds*
  authentication; it never rejects for absence. `SecurityFilterConfig` becomes the single source of truth for who may
  reach what, and the two-lists-to-keep-in-sync problem in `CLAUDE.md` section 4 goes away rather than doubling.
- `authorizeHttpRequests` expresses the whole policy declaratively, per method.

## The catch: 401 becomes 403 unless an entry point is configured

Handing rejection to the filter chain reintroduces exactly the failure mode
[security-error-handling.md](security-error-handling.md) documents. `SecurityFilterConfig` configures no
`AuthenticationEntryPoint` (no `.httpBasic()`, no `.oauth2ResourceServer()`), so Spring Security falls back to
`Http403ForbiddenEntryPoint` — and an anonymous request to a protected route would answer **403 Forbidden** where it
should answer **401 Unauthorized**.

So this change is not optional alongside the filter change:

```java
.exceptionHandling(ex -> ex
    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))  // anonymous  -> 401
    .accessDeniedHandler(...)                                                     // wrong role -> 403
)
```

This is the correct semantics rather than a workaround, and it matches the status-code rules in the root `CLAUDE.md`:
401 for missing or invalid credentials, 403 for authenticated but not permitted.

## Resulting authorization rules

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/error").permitAll()
    .requestMatchers("/public/**", "/health").permitAll()

    // public reads
    .requestMatchers(HttpMethod.GET, "/recipes/**", "/ingredients/**", "/categories/**",
                                     "/tags/**", "/units/**", "/media/**").permitAll()

    // reference data curated by admins
    .requestMatchers(HttpMethod.POST,   "/categories", "/tags", "/units").hasRole("ADMIN")
    .requestMatchers(HttpMethod.PUT,    "/categories/**", "/tags/**", "/units/**",
                                        "/ingredients/**").hasRole("ADMIN")
    .requestMatchers(HttpMethod.DELETE, "/categories/**", "/tags/**", "/units/**",
                                        "/ingredients/**").hasRole("ADMIN")

    // any authenticated user
    .requestMatchers(HttpMethod.POST, "/ingredients", "/media").hasRole("USER")
    .requestMatchers(HttpMethod.POST, "/recipes").hasRole("USER")

    // existing public routes
    .requestMatchers(HttpMethod.POST, "/users/create").permitAll()
    .requestMatchers(HttpMethod.PUT,  "/users/reinit-password").permitAll()
    .requestMatchers(HttpMethod.POST, "/auth/**").permitAll()

    .requestMatchers("/admin/**").hasRole("ADMIN")
    .requestMatchers("/users/**").hasRole("USER")
    .anyRequest().authenticated()
)
```

Order matters: these matchers are evaluated top to bottom and the first match wins, so the `GET` rules must precede
the broader path rules or the admin rules would swallow public reads of `/tags`.

Note that admins need the `USER` role too, or `hasRole("USER")` rules lock them out. That is already true today and is
a property of `UserService.createUser` assigning `USER` to everyone; worth a check when the first admin account is
created by hand.

## Where role rules stop and ownership begins

URL rules can express "you must be an admin". They cannot express "you may edit this recipe because you wrote it" —
that depends on a row in the database.

So `PUT /recipes/{id}` and `DELETE /recipes/{id}` are `authenticated()` at the URL layer, and `RecipeService` performs
the real check after loading the recipe:

```java
if (!recipe.getAuthor().getId().equals(currentUserId) && !currentUserIsAdmin) {
    throw new ForbiddenOperationException("...");
}
```

which the exception advice maps to 403 (see [api-error-handling.md](api-error-handling.md)).

Keeping it in the service, not the controller, matters: it is a business rule, it needs the loaded entity, and putting
it in the service means it cannot be bypassed by a second caller reaching the same operation another way.

`@EnableMethodSecurity` with `@PreAuthorize` was considered and not adopted. It would add a third authorization
mechanism next to the URL rules and the service check, and the expression that actually needs writing here
(`@PreAuthorize("@recipeService.isAuthor(#id, authentication)")`) is a string-based indirection around a plain Java
`if` — harder to read, harder to debug, and no more secure.

## Testing

This is the part a `standaloneSetup` MockMvc test cannot cover, for the same reason recorded in
`SecurityErrorHandlingTest`: no security filter chain, no filter, nothing under test. It needs a `@SpringBootTest`
against the real chain, asserting at minimum:

| Case                                              | Expected |
|---------------------------------------------------|----------|
| `GET /recipes` with no header                      | 200      |
| `GET /recipes` with a valid token                  | 200, and the service sees the user |
| `GET /recipes` with a malformed/expired token      | 401      |
| `POST /recipes` with no header                     | 401 (not 403 — this is the regression to guard) |
| `POST /recipes` with a valid `USER` token          | 201      |
| `PUT /recipes/{id}` by a non-author `USER`         | 403      |
| `PUT /recipes/{id}` by an `ADMIN`                  | 200      |
| `POST /tags` by a plain `USER`                     | 403      |

The `POST /recipes` anonymous case is the important one: it is 401 only because of the explicit
`AuthenticationEntryPoint`, and it silently becomes 403 if that line is ever dropped.
