# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

`recettes-back` is a Spring Boot 4.0.1 (Java 25) REST API providing user account management and JWT-based
authentication. It is built with Maven and the Maven Wrapper.

This is a work-in-progress hobby project (a recipe library) being built solo, for personal/friends-and-family use. It
has no revenue goal — favor correctness, learning, and code quality over shipping speed or monetization concerns.

Always use the latest available version of each technology (Java, Spring Boot, and every dependency in `pom.xml`)
when adding or upgrading dependencies, unless the user says otherwise.

This project follows clean code principles (DRY, KISS, SRP) as a baseline for every change — see my global
`~/.claude/CLAUDE.md` for the full motto.

### HTTP status codes

Returned status codes must accurately reflect the real outcome of the request, not just "success vs. generic failure".
In particular, avoid collapsing every error into 400/500 — pick the code that matches the actual condition, e.g.:

- `404 Not Found` — the referenced resource doesn't exist (not `400`).
- `409 Conflict` — the request conflicts with existing state (e.g. a duplicate username on create), not `400`.
- `401 Unauthorized` — missing/invalid credentials or token.
- `403 Forbidden` — authenticated but not allowed to perform the action (e.g. a disabled account, wrong role).
- `400 Bad Request` — reserved for malformed/invalid input (validation failures).
- `500 Internal Server Error` — reserved for unexpected/unhandled failures, not for expected business-rule rejections.

When adding or touching an endpoint, check that its error branches map to the specific status that matches what actually
happened, rather than reusing a generic catch-all.

### Documentation

There is no external wiki (no Confluence) for this project — all product and architectural knowledge has to live in the
repo, in `docs/`. When you make or implement a non-obvious design/architecture decision (why a library or pattern was
chosen, a trade-off, a rejected alternative) or a product decision (what a feature is for, how it's meant to behave),
write it down in `docs/` rather than only in a commit message or chat, and keep it up to date when that decision
changes. This file (`CLAUDE.md`) stays focused on operational guidance for working in the codebase;
`docs/` is for the "why" behind the product and the architecture.

## Commands

All commands use the Maven Wrapper (`mvnw.cmd` on Windows, `./mvnw` in a POSIX shell).

```bash
./mvnw clean package        # build (compiles, runs tests, packages jar)
./mvnw spring-boot:run      # run the app locally (needs PostgreSQL, see below)
./mvnw test                 # run the full test suite
./mvnw test -Dtest=RecettesBackApplicationTests#contextLoads   # run a single test
```

There is no linter/formatter plugin configured in `pom.xml` (no Checkstyle/Spotless).

### Testing

Testing is a priority for this project, with a target of >90% **line** coverage (not class coverage — a class with
uncovered branches counts against this even if every class in the package has at least one test). This is enforced by
the `jacoco-maven-plugin` in [pom.xml](pom.xml): `./mvnw test` (and therefore `./mvnw clean package`) runs the tests,
generates an HTML report at `target/site/jacoco/index.html`, and **fails the build** if line coverage across the whole
project drops below 90%. Whenever a request or response format is added or changed (a controller endpoint, a DTO in
`models`, etc.), add or update a matching sample request in `src/test/requests` alongside the actual test code.

Sample requests in `src/test/requests` are `.http` files (IntelliJ HTTP Client / VS Code REST Client syntax:
`@variable = value` definitions, `###`-separated request blocks). They are organized **one file per endpoint or feature
flow** (e.g. `password-reset.http`), not one file per DTO. Each file must walk through the full scenario for that flow,
not just a single happy-path payload: the success case plus the meaningful failure/edge cases (validation errors,
not-found/conflict, and any security-relevant case such as an invalid, expired, or replayed token). Give each request
block a `###` comment naming the scenario and the status code it's expected to return, since these files are read and
run manually rather than asserted by the automated suite. Where a step can't be scripted end-to-end (e.g. a token that
only ever exists in an emailed link), use a placeholder variable and say so in a comment rather than silently skipping
the scenario.

### Local database

The app expects PostgreSQL reachable at the URL/credentials in
[application.properties](src/main/resources/application.properties). `spring.jpa.hibernate.ddl-auto=update` means
Hibernate creates/updates the schema automatically from the JPA entities — there are no migration scripts (no
Flyway/Liquibase). A `USER` row must exist in the `roles` table for `POST /users/create` to succeed
(`UserService.createUser` looks it up by name and assigns it to every new user; it does not create the role itself).

### Local secrets (`.env`)

`application.properties` references `${DB_USERNAME}`, `${DB_PASSWORD}`, `${MAIL_USERNAME}`, `${MAIL_PASSWORD}` — never
real values — and the first line, `spring.config.import=optional:file:.env[.properties]`, is what makes those resolve:
Spring Boot does **not** read `.env` files natively (that's a Node/Docker-Compose convention), so without that line
every placeholder fails to resolve and the app won't start. The `[.properties]` hint tells the Config Data loader to
parse the extension-less `.env` file as `key=value` Java `.properties` syntax. Copy
[.env.example](.env.example) to `.env` (gitignored, never commit it) and fill in real values. Because it's parsed as
`.properties`, **never quote values** (`'...'`/`"...'`) even if they contain spaces (e.g. a Gmail app password) — Java
`.properties` parsing doesn't strip quotes, so they'd become part of the literal value and silently break auth.

## Architecture

Standard layered structure under `ilenreste.unpeu.recettesback`:

- `controllers` — REST endpoints (`AuthenticationController`, `UserController`). Thin: validate/delegate to a service,
  translate exceptions to HTTP statuses (`IllegalStateException` → 400, anything else → 500).
- `services` — business logic (`UserService` for create/update, `DatabaseUserDetailsService` implementing Spring
  Security's `UserDetailsService` for login lookups, `PasswordResetService`/`PasswordResetTokenService` for the
  forgot-password flow, `MailService`/`SmtpMailService` for outgoing email). All service classes live directly under
  `services` — do not create feature-specific packages (e.g. `mail`, `notifications`) for them, even for a single
  interface + implementation pair.
- `repositories` — Spring Data JPA repositories (`UsersRepository`, `RolesRepository`, `UserRolesRepository`).
- `entities` — JPA entities (`UserEntity`, `RoleEntity`, `UserRolesEntity`). Users and roles are many-to-many via the
  `user_roles` join table.
- `models` — DTOs, split into `auth` (`AuthenticationRequest`) and `users` (`CustomUserDetails`, and
  `users.requests` for `CreateUserRequest`/`UpdateUserRequest`). `UpdateUserRequest` uses `Optional<T>` fields for
  partial updates — only present fields are applied in `UserService.updateUser`.
- `configuration` — Spring `@Configuration` classes wiring security and JWT infrastructure.
- `filters` — `JwtAuthenticationFilter`, a custom `OncePerRequestFilter`.

### Authentication flow

The API is a stateless resource server that also issues its own JWTs (self-contained auth, no external IdP):

1. `RsaKeyConfig` generates a fresh 2048-bit RSA `KeyPair` **in memory at application startup** (not persisted, not
   loaded from config). `JwtIssuerConfig` builds the `JwtEncoder`/`JwtDecoder` beans from that key pair. Consequence:
   tokens do not survive an app restart, and a multi-instance deployment would need a shared/fixed key since each
   instance would otherwise mint incompatible keys.
2. `POST /auth/login` (`AuthenticationController`) authenticates via Spring Security's `AuthenticationManager`
   (backed by `DatabaseUserDetailsService` + `BCryptPasswordEncoder`, wired in `SecurityBeansConfig`), then hand-builds
   a JWT with `issuer=self`, a `userId` claim, and a `roles` claim (list of role names without the `ROLE_` prefix).
3. Every other request is authenticated by `JwtAuthenticationFilter`, added before
   `UsernamePasswordAuthenticationFilter` in `SecurityFilterConfig`. It reads the `Authorization: Bearer <token>`
   header itself, decodes it with the `JwtDecoder`, maps the `roles` claim to `ROLE_*` `GrantedAuthority`s, and puts a
   `JwtAuthenticationToken` in the `SecurityContextHolder`. Controllers read identity back out via
   `@AuthenticationPrincipal Jwt` and `jwt.getClaims().get("userId")` (see `UserController.updateUser`) rather than
   through `CustomUserDetails`, which is only used during the login step.
4. `shouldNotFilter` in `JwtAuthenticationFilter` and the `authorizeHttpRequests` rules in `SecurityFilterConfig`
   both hardcode the same public endpoints (`POST /users/create`, `POST /auth/**`) — keep both in sync when adding a new
   public route. Session management is `STATELESS`; the `spring-boot-starter-session-jdbc` dependency is present but
   unused by the current filter chain.
5. `SecurityFilterConfig` also `permitAll()`s `/error`. A `@Valid` failure is reported via the servlet container's
   `sendError`, which internally forwards to `GET /error` to render the body — that forward re-enters the security
   filter chain as its own request, and without this rule it gets rejected by the default
   `Http403ForbiddenEntryPoint` (no `AuthenticationEntryPoint` is configured), turning every validation failure into an
   empty `403` instead of `400`. `JwtAuthenticationFilter` doesn't need the same treatment: as an
   `OncePerRequestFilter` it already skips `ERROR`-dispatch requests by default. See
   `docs/security-error-handling.md`. This is also why a plain `MockMvcBuilders.standaloneSetup(...)` controller test
   (no security filter chain) can't catch this class of bug — it needs a real `@SpringBootTest` hitting the actual
   embedded server.
