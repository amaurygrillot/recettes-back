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

| Request                            | Today               | After                                                          |
|------------------------------------|---------------------|----------------------------------------------------------------|
| No `Authorization` header          | 401 from the filter | continue the chain unauthenticated; the chain decides          |
| Header not starting with `Bearer ` | 401 from the filter | continue unauthenticated — no credential was offered           |
| `Bearer <valid token>`             | authenticated       | authenticated (unchanged)                                      |
| `Bearer <invalid/expired>`         | 401 from the filter | 401 from the filter, **except on the credential routes below** |

A **present but invalid** token still fails fast with a 401. That distinction is deliberate: no credentials is a
legitimate way to call a public endpoint, but a broken token is a caller who thinks they are authenticated and is not,
and silently downgrading them to anonymous turns an expired session into a confusing 403 on the next write.

Note the second row is a *different* case from the fourth, and the two must not be collapsed: `Authorization: Basic
abc` is a caller offering no bearer credential at all, so it is treated exactly like a missing header, while
`Bearer <garbage>` is a caller offering one that does not work.

With that in place:

- `shouldNotFilter` is **deleted**. The filter must run on every request, including the public ones — that is the whole
  point, since skipping it is what makes an authenticated `GET /recipes` blind to its own caller.
- `SecurityFilterConfig` becomes the single source of truth for *who may reach what*, and the two-lists-to-keep-in-sync
  problem in `CLAUDE.md` section 4 goes away: the six-plus recipe routes are declared once, in `authorizeHttpRequests`,
  and never in the filter.
- `authorizeHttpRequests` expresses the whole policy declaratively, per method.

### The exception: routes that hand out credentials

`PUBLIC_ENDPOINTS` does **not** simply disappear. It shrinks to three entries and changes meaning — from "skip the
filter entirely" to "on this route, never *reject*" — and is renamed accordingly:

```java
private static final List<RequestMatcher> CREDENTIAL_ENDPOINTS = List.of(
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/auth/**"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/users/create"),
        PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.PUT, "/users/reinit-password")
);
```

These are the routes whose entire purpose is to *obtain* a working credential, and rejecting them for holding a broken
one is a deadlock. The scenario is not hypothetical: `RsaKeyConfig` generates the RSA key pair in memory at every
startup, so **every restart invalidates every token in circulation**. A browser client that attaches its stored token to
all requests — the default shape of an SPA HTTP interceptor — would then be 401'd by the filter on `POST
/auth/login` itself, before `AuthenticationController` ever runs, and could never obtain a fresh token. The only escape
is clearing browser storage by hand. `PUT /users/reinit-password` is worse still: a dead session is precisely *why*
someone is on the password-reset screen.

Today this can't happen only because `shouldNotFilter` skips those three routes outright. Deleting the list without
replacing this behaviour is a regression dressed up as a simplification.

The resulting filter:

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
        chain.doFilter(request, response);          // no credential offered — the chain decides
        return;
    }

    try {
        Jwt jwt = jwtDecoder.decode(authHeader.substring(7));
        SecurityContextHolder.getContext().setAuthentication(toAuthentication(jwt));
    } catch (JwtException jwtException) {
        if (CREDENTIAL_ENDPOINTS.stream().noneMatch(matcher -> matcher.matches(request))) {
            log.warn("Rejecting request with an undecodable JWT", jwtException);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        log.debug("Ignoring an undecodable JWT on a credential endpoint", jwtException);
    }

    chain.doFilter(request, response);
}
```

Two details worth keeping. The list is **stable** — it does not grow when public routes are added, because a public
*read* route needs nothing from it, which is what makes this a genuinely smaller obligation than the old
`PUBLIC_ENDPOINTS`. And a malformed token is client-supplied input, so it logs at `warn`/`debug`, not the `error` the
current filter uses: an expired token is a routine event and should not page anyone.

One doc to update alongside the rename: [password-reset.md](password-reset.md) points at
`JwtAuthenticationFilter.PUBLIC_ENDPOINTS` as the reason its two routes are reachable without a JWT. That is still true
today, and becomes wrong the moment this lands — the reason changes from "the filter is skipped" to "the filter does not
reject here, and `SecurityFilterConfig` permits it".

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

| Case                                                      | Expected                                              |
|-----------------------------------------------------------|-------------------------------------------------------|
| `GET /recipes` with no header                             | 200                                                   |
| `GET /recipes` with a valid token                         | 200, and the service sees the user                    |
| `GET /recipes` with `Authorization: Basic abc`            | 200 — no bearer credential offered, same as no header |
| `GET /recipes` with `Bearer <expired>`                    | 401 — a broken credential is not the same as none     |
| `POST /auth/login` with `Bearer <expired>` still attached | 200 — the lockout regression to guard                 |
| `PUT /users/reinit-password` with `Bearer <expired>`      | 400/200 per the flow — never 401 from the filter      |
| `POST /recipes` with no header                            | 401 (not 403 — this is the other regression to guard) |
| `POST /recipes` with a valid `USER` token                 | 201                                                   |
| `PUT /recipes/{id}` by a non-author `USER`                | 403                                                   |
| `PUT /recipes/{id}` by an `ADMIN`                         | 200                                                   |
| `POST /tags` by a plain `USER`                            | 403                                                   |

Rows three and four exist as a pair on purpose: they are the two halves of the distinction drawn in
[The change](#the-change), and a test suite that asserts only one of them will not notice if the filter starts rejecting
every request carrying an unrelated `Authorization` header.

Two rows guard regressions rather than features. `POST /recipes` anonymous is 401 only because of the explicit
`AuthenticationEntryPoint`, and silently becomes 403 if that line is ever dropped. `POST /auth/login` with a dead token
is 200 only because of `CREDENTIAL_ENDPOINTS`, and silently becomes an unrecoverable 401 if that list is ever
"simplified" away.
