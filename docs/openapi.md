# OpenAPI: a contract-first API

Status: **implemented**. The REST API is described by one hand-written file,
[`src/main/resources/openapi/openapi.yaml`](../src/main/resources/openapi/openapi.yaml). The build turns it into the
Spring `*Api` interfaces, the controllers implement them, springdoc serves what the running application actually
exposes as Swagger UI, and a test fails when the two disagree.

This is the one file that describes the whole API: every route, who may call it, what it takes, every status it can
answer and the shape of every body. Read it before reading any controller.

## How the pieces fit

```
openapi.yaml ──(openapi-generator, every build)──▶ target/generated-sources/openapi/.../api/*Api.java   interfaces only
                                                                  ▲
   models/*  (hand-written records, unchanged) ◀── schemaMappings ┘   the generator is told which record each schema is
                                                                  │
controllers/*  implement the *Api interfaces ◀────────────────────┘

running app ──(springdoc)──▶ /v3/api-docs  +  Swagger UI at /swagger-ui.html       only with SPRINGDOC_ENABLED=true
                                   │
                                   └──▶ OpenApiContractTest: must equal openapi.yaml
```

| Piece | Where |
|---|---|
| The contract | `src/main/resources/openapi/openapi.yaml` |
| Generator configuration | the `openapi-generator-maven-plugin` block in `pom.xml` |
| Generated interfaces (never edit, never commit) | `target/generated-sources/openapi`, package `ilenreste.unpeu.recettesback.api` |
| What springdoc serves, and its corrections | `configuration/OpenApiConfig` |
| Dropping the generated `produces` and `consumes` conditions | the `strip-generated-media-type-conditions` step in `pom.xml` |
| Dev-only switch | `springdoc.*` in `application.properties`, behind `SPRINGDOC_ENABLED` (off unless set) |
| The tests | `src/test/java/.../contract/` |

## Decisions

### Contract-first, and only the interfaces (Option B)

The YAML is written first and the code conforms to it, rather than the other way round. What the generator produces is
**the interfaces and nothing else**: no models, no controllers, no delegates.

- **Why contract-first.** An API is something other people (a future frontend, an AI client, you in six months) depend
  on, and a contract that is only ever *derived* from code can only be reviewed by reading the code. Written first, a
  change to the API is a diff to one file that says exactly what a client will see, and the compiler then forces the
  controllers to follow: add an operation to the YAML and `TagController` stops compiling until it implements it.
- **Why interfaces only.** A generated interface is a compile-time guarantee of the route, the parameters and the
  return type, at no cost to anything else. Generated *models* and *controllers* are not, here, because the records in
  `models` are doing real work that a generator would not reproduce: `UpdateRecipeRequest` and `UpdateUserRequest` are
  `Optional`-based so an absent field differs from a null one, `@NotEmptyIfPresent` is a custom constraint,
  `@JsonProperty(required = false)` shapes the MCP tools' input schema, and the mappers and `RequestValidator` are
  written against them. Replacing them would have meant re-deriving all of that, in generated code that cannot hold
  it.
- **So the generator is pointed at the records.** `generateModels` is off and `schemaMappings` in `pom.xml` says, for
  each schema, which existing record it is. The interface then imports that record directly.

Rejected:

- **Full generation** (models and all): above. It is the usual way to use the tool and the wrong one for a codebase that
  already has meaningful DTOs.
- **Code-first only** (springdoc as the author, no YAML): the contract then has no existence before the code, and no
  place for what annotations express badly - which roles a route needs, which errors it can answer, why. It is also
  the one option where "does the API still do what we said" has no answer, because nothing was said.
- **A hand-written YAML policed only by a conformance test**, no generation: the fallback if the generator could not be
  mapped onto the records. It could, so nothing here uses it, but the test below would work unchanged under it.

### What is deliberately not in the contract

`POST /mcp` (the MCP server) is not REST: it is JSON-RPC over Streamable HTTP, with its own tool schemas generated from the
request records, and it is documented in [mcp-server.md](mcp-server.md). It is absent from the YAML and from what springdoc
serves, and the `@JsonProperty(required = false)` annotations those tools depend on are untouched. `/error` is
Spring's, and absent for the same reason.

### springdoc is a viewer and a drift check, never the author

springdoc reflects on the running application, so what it serves is *what the code does*. That is exactly the right
input for a drift check and exactly the wrong one for a contract, so it is used for both of those and never to write the
YAML. `OpenApiContractTest` compares the two; Swagger UI renders the live one, so what a developer clicks on is what is
really deployed.

It works because the generated interfaces carry swagger annotations (`documentationProvider=springdoc`,
`annotationLibrary=swagger2`): the YAML reaches springdoc *through* the interfaces, so the served spec is the YAML as
the code implements it, with the records' shapes added by reflection.

### Swagger UI is a development aid, off unless asked for

`springdoc.api-docs.enabled` and `springdoc.swagger-ui.enabled` both read `${SPRINGDOC_ENABLED:false}` in
`application.properties`: **off unless `SPRINGDOC_ENABLED=true` is set**, in the environment or in the local `.env` (which
is parsed as `.properties`, so the line is just `SPRINGDOC_ENABLED=true`; the IntelliJ run configuration's environment
variables work too). The default is off, and not "on, except under a `prod` profile", because a deployment that forgets a
switch must hide the API description, not publish it. `OpenApiSwitchTest` proves that with the switch off the spec and the
UI answer 404, and that the committed default is off.

The paths `/v3/api-docs/**`, `/swagger-ui.html` and `/swagger-ui/**` are `permitAll()` in `SecurityFilterConfig` and
nowhere else, never in `JwtAuthenticationFilter`; with springdoc off they simply do not exist, so the rule exposes nothing.
`OpenApiConfig` is only loaded when springdoc is on, so nothing reads the YAML at startup otherwise.

The committed `openapi.yaml` is the artifact whatever the switch says. Swagger UI's **Authorize** takes the token from
`POST /auth/login` (the raw body, no `Bearer ` prefix). Tokens die at every restart (`RsaKeyConfig`), so the UI is not set
to remember one across reloads: a remembered token would keep showing "Authorized" while every call answered 401.

### Spring Boot 4.1 and Jackson 3

The upgrade was a precondition: the generator's Boot 4 / Jackson 3 support (`useSpringBoot4` and `useJackson3`, since
7.16) needs it, and Boot 4 is where this project already was. It was its own commit, green before any of this.

| Dependency | Version | Note |
|---|---|---|
| Spring Boot | 4.1.1 | latest 4.1.x; 4.2.0-M2 is a milestone |
| Spring AI | 2.0.1 | already the latest GA; 2.1.0-M1 is a milestone and was held back |
| springdoc-openapi | 3.1.1 | built against Boot 4.1 |
| openapi-generator-maven-plugin | 7.26.0 | |
| PostgreSQL driver | 42.7.14 | newer than Boot's managed 42.7.13, overridden through `postgresql.version` |
| Lombok | 1.18.48 | newer than Boot's managed 1.18.46, overridden through `lombok.version` |
| Maven (wrapper) | 3.9.16 | |
| JaCoCo | 0.8.15 | already the latest |
| maven-antrun-plugin | 3.2.0 | only runs the `strip-generated-media-type-conditions` step |
| swagger-annotations-jakarta | 2.2.55 | pinned: Spring AI's model module drags in 2.2.38, which Maven would otherwise pick over the 2.2.55 springdoc's swagger-core is built against |

**Jackson.** The application was already entirely on Jackson 3 (`tools.jackson.*`); the only `com.fasterxml.jackson`
imports are `@JsonProperty` and `@JsonIgnore`, and `jackson-annotations` keeps that package name in Jackson 3, so
there was nothing to migrate. The one place Jackson 2 is on the classpath is **inside springdoc**: swagger-core 2.2.x
is still built on it and uses it only to write the OpenAPI document. No code of ours touches it.

## Adding or changing an endpoint

1. **Edit `openapi.yaml` first.** The operation: `tags`, a unique `operationId`, `security` (`[]` for public,
   `[{bearerAuth: []}]` otherwise, plus `x-required-role: USER` or `ADMIN` when a role is needed), the parameters, the
   request body, and **every status the code really returns**, with the problem responses taken from
   `components/responses` (404 for a path id that names nothing, 409 for a conflict, 400 for invalid input, 401/403 for
   security, 429/503 with `Retry-After`; see the status-code rules in `CLAUDE.md`). A request body gets
   `x-codegen-request-body-name: request`, or the generated parameter is named after its class.
2. **A new request or response type?** Write the record in `models/<domain>/...` as before, add its schema to
   `components/schemas` with **the same name as the record**, and add one `<schemaMapping>` line for it in `pom.xml`. A
   schema an operation references and the pom does not map fails the build, which is the reminder.
3. **Build** (`./mvnw compile`). The `*Api` interface is regenerated; the controller that does not yet implement the new
   method no longer compiles.
4. **Implement the generated method** in the controller (`@Override`, no routing annotations: they come from the
   interface). Keep it thin, as ever: no `try/catch`, a service that returns a DTO or throws.
5. **Run the tests.** `OpenApiContractTest` fails if the code and the YAML disagree about anything a client sees;
   `OpenApiSecurityConformanceTest` fails if the YAML's idea of who may call it disagrees with `SecurityFilterConfig`.
   A new route still needs its rule in `SecurityFilterConfig`: the YAML describes access, it does not grant it.
6. Add or update the matching sample in `src/test/requests`, as for any change to a request or response format.

Changing a field of a record without touching the YAML fails `OpenApiContractTest`; so does the reverse. That is the
point of it.

## What the generator cannot do, and what is done about it

None of these changes what an endpoint does. Each one is a consequence of generating from a tool with its own limits,
and each is covered by a test.

### The two paged searches are hand-written (`x-internal`)

`GET /recipes` and `GET /ingredients` return a Spring Data `Page<T>`. The generator copies the return type into the class
literal of an `@Schema(implementation = ....class)` annotation, and a generic type is not valid there, so the output
does not compile. Both operations are marked `x-internal: true`, which makes the generator skip them, and their
signatures are written by hand in `RecipeController.searchRecipes` and `IngredientController.searchIngredients` with the
annotations the generator would have produced.

They stay honest because the YAML still describes them in full and `OpenApiContractTest` compares the entry with what
springdoc reads from the hand-written method, including the parameters its `@ParameterObject` search record expands to
and the shape of the page. The YAML lists the criteria as individual query parameters; the code binds them into
`RecipeSearchRequest` / `IngredientSearchRequest`, and the test is what ties the two together.

### The response media types the generator writes are wrong, so the served spec corrects them

The generator writes every response of an operation with the operation's *whole* list of media types, so a `201`
claims to be `application/problem+json` and a `404` claims to be `application/json`. `OpenApiConfig.servedSpecCustomizer`
fixes that in what springdoc serves: a 4xx/5xx keeps only `application/problem+json`, anything else drops it. Four more
corrections go through the same place:

- With no `produces` to read, springdoc labels a body `*/*`. For a JSON-shaped one (an object, an array or a reference
  to one, as the two hand-written searches return) that is relabelled `application/json`; a text or binary body keeps
  the wildcard, so nothing is advertised as JSON that is not.
- With no `consumes`, springdoc calls a multipart upload `application/json` and marks it optional. A body with a binary
  part is relabelled `multipart/form-data`, and required when its schema has required parts.
- A `304` has no body, and springdoc's habit of copying the return type onto it is undone.
- `Pageable` expands to `page`, `size` **and `sort`**: the services ignore `sort` on purpose (see `RecipeSortField`), so
  it is removed rather than advertised.

### The generated `produces` and `consumes` conditions are removed, so a client's headers still work

The generator copies every media type that appears in any response of an operation into a `produces` condition on each
mapping. For an operation with no success body, that is only `application/problem+json`, and a client that sends
`Accept: application/json` (most HTTP wrappers do, on every call) would get a **406** from the handler mapping where it
used to get a 204. `POST /auth/login`, whose token is plain text, would get one too. It also adds `consumes`, which turns a
request with no body and no `Content-Type` (a bare `curl -X POST`) from the 400 "required request body is missing" it has
always been into a 415. Both are behaviour changes made in the name of documentation.

So the `strip-generated-media-type-conditions` step in `pom.xml` (a regular expression, run by `maven-antrun-plugin` right
after the generator) deletes both from the generated sources, and a mapping is again a method and a path. A second step
fails the build if a generated interface still says `produces =` or `consumes =`, so a generator that one day spells them
differently cannot turn this into a silent no-op. The media types stay in the contract and in the swagger annotations;
only the runtime conditions go. Nothing written by hand declares either, and `BasicErrorController`, which needs its own
`produces` to start, is not generated, so it is untouched.

This was first a runtime class that replaced Boot's `RequestMappingHandlerMapping` and stripped the conditions as the
mappings were built. It worked, and it was the wrong depth: a hook into Spring's internals to undo what a build step wrote,
with the real cause one step upstream. `GeneratedApiBehaviourTest` pins every shape that broke.

### Login does not validate its body, and still must not

`POST /auth/login` has never validated its body: a blank credential is a wrong credential and answers 401. The generator
puts `@Valid` on every request body, so the `@NotBlank` on `AuthenticationRequest` would have started answering 400.
Those annotations are now `@Schema(requiredMode = REQUIRED)`, which keeps `required` in the published schema without
the validation. If a 400 for blank credentials is wanted, that is a one-line change back to `@NotBlank` and a deliberate
decision, not a side effect.

### Smaller things

- **`RecipeIntroductionRequest.isBlank()`** looked like a bean getter, so the served schema listed a `blank` property
  nobody sends or receives. It is `@JsonIgnore`d. The MCP tools' input schema, built by a different generator, never
  listed it, with or without the annotation; `McpEndpointTest` pins that.
- **`ProblemDetail`** is mapped onto Spring's own class, which Jackson flattens with a mixin that springdoc's reflection
  does not see, so the served schema has a `properties` member the wire does not. The contract test skips that one
  schema; the wire shape is asserted by the integration tests that read real error bodies.
- **Response headers** (`Location`, `Retry-After`, `ETag`, ...) are in the YAML but not in what springdoc serves: the
  generator does not emit them into the annotations. They are documentation of the contract, not checked.
- **`info` and the `bearerAuth` scheme** are read from the YAML by `OpenApiConfig` rather than repeated in Java; there is
  no annotation for "this API authenticates with a bearer token". Because the served spec takes them from the YAML, the
  contract test does not compare them: they could only ever match.
- **The `Page` JSON shape** is what `PageImpl` serialises to today, and Spring Data logs a warning, the first time a page is serialised, that it is
  not guaranteed stable. `PageIngredientResponse`, `PageRecipeSummaryResponse`, `PageableObject` and `SortObject` in the
  YAML mirror it, and the contract test will notice the day an upgrade moves it. Moving to Spring Data's
  `PagedModel` (`@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`) is the supported fix; it changes the wire
  format, so it was not done here.

## The tests

| Test | What it proves |
|---|---|
| `OpenApiContractTest` | The served `/v3/api-docs` and `openapi.yaml` agree on every operation (id, tags, access, parameters, request bodies, statuses and media types) and every schema (properties, types, required, enums). It reduces each to a set of one-line facts, so a failure lists exactly what is in one and not the other. For a generated operation the served spec is rendered from the YAML, so most of that can only differ for the hand-written searches, the schemas and an endpoint added in code alone. |
| `OpenApiSecurityConformanceTest` | Every operation in the YAML is sent, harmlessly, to the real filter chain as an anonymous caller, a logged-in caller with no role, a `USER` and an `ADMIN`; the answer must be what `security` and `x-required-role` promise, never a 5xx, and **a status the operation's `responses` document**. That last check is the only place the YAML's statuses meet the running code. |
| `OpenApiSwitchTest` | With `SPRINGDOC_ENABLED=false` the spec and Swagger UI answer 404, and the committed default is off. |
| `GeneratedApiBehaviourTest` | A client that only accepts JSON still gets through to operations with no success body and to login, a request with no body and no `Content-Type` is still a 400, an invalid search parameter is still a 400 whatever the client accepts, and blank login credentials are still a 401. |

The contract test is what makes this safe to leave alone: it fails when a controller is changed without the YAML (an
endpoint added, a parameter dropped), when the YAML is changed without the code (for the parts the generated interfaces
cannot enforce: the hand-written searches, the schemas), and when a DTO record gains or loses a field.

### How "no behaviour changed" was checked

Beyond the existing suite passing untouched, the original `master` build and this branch were run side by side against the
same database and sent the same 302 requests: every `Accept` header, requests with no body, an empty body, a malformed body
and the wrong content type, the upload shapes, and each of anonymous, `USER` and `ADMIN`. Status, content type and error
text were compared. The first run found two differences (the `produces` and `consumes` conditions above), both fixed; the
last found none. The two 500s both builds share - a non-multipart `POST /media`, where `MultipartException` reaches the
catch-all handler - are an existing bug, left alone because fixing it changes a status; mapping it to a 400 or 415 is a
small follow-up.

Repeat the exercise when the generator or springdoc is upgraded: it is the only check that sees the mappings Spring really
builds.

## When the tools are upgraded

The generator's templates are **not** forked, on purpose; every workaround above is in our code and is tested. After
bumping `openapi-generator-maven-plugin` or springdoc, run the suite: a changed generated annotation or a springdoc
default will show up as a contract-test failure that names the fact. The two things worth re-checking by hand are
whether the generator can now express a generic return type (then `x-internal` can go) and whether it still unions the
media types across responses (then the customizer can shrink).
