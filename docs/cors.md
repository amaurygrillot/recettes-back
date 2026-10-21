# CORS

## What it does

A browser lets a page call an API on a **different origin** (scheme + host + port) only if the API says so. The front
end is served from its own origin (Vite on `http://localhost:5173` in development), so without this the browser blocks
every call it makes to the API, whatever the API answers.

The policy is built by `CorsConfig` from the `CorsProperties` record and handed to the security chain in
`SecurityFilterConfig` (`http.cors(...)`).

| Part                    | Value                                                                                   |
|-------------------------|-----------------------------------------------------------------------------------------|
| Allowed origins         | `app.cors.allowed-origins` — an explicit list, default `http://localhost:5173`          |
| Methods                 | `GET`, `POST`, `PUT`, `DELETE`, `OPTIONS`                                               |
| Request headers         | `Authorization`, `Content-Type`, `Accept`                                               |
| Exposed response headers | `Location`, `Retry-After`                                                              |
| Credentials             | not allowed (`Access-Control-Allow-Credentials` is never sent)                          |
| Preflight cache         | `app.cors.max-age`, default `1h` (browsers cap it themselves — Chromium at 2 hours)     |

`Location` is how the front end follows a create (`201`); `Retry-After` is how it learns when to retry after a `429` or a
`503`. A cross-origin script cannot read a response header that is not listed in `Access-Control-Expose-Headers`, so
without them the headers are on the wire and invisible to the code that needs them.

## Why an explicit list of origins, never `*`

- **The list is the policy.** `*` is the absence of one: every site a user visits could then script this API from their
  browser. The API is for friends and family; the set of origins that should talk to it is small and known.
- `CorsProperties` **refuses to start** on a wildcard (`*`, `https://*.example.com`), on an empty list, and on anything
  that is not a bare `scheme://host[:port]` — most often a trailing slash. A browser sends `Origin` without one, so
  `https://recettes.example.com/` would never match and the failure would look like a front end that mysteriously cannot
  reach the API. Failing at startup, with the offending value in the message, is cheaper.
- The configured list is logged at `INFO` on startup (`CORS allowed origins: [...]`), so the answer to "why is my front
  end blocked" is the first thing in the log.

## Why no credentials

The JWT travels in the `Authorization` header, which the front end attaches itself. There are no cookies and no
server-side session (`SessionCreationPolicy.STATELESS`), so there is nothing for the browser to send automatically and
nothing to protect with `Access-Control-Allow-Credentials`. Leaving it off also keeps the option of a wildcard-free,
credential-free policy simple to reason about: a malicious page on another origin cannot ride on a logged-in session,
because there is no such session. If cookies are ever introduced (a refresh-token cookie, say), this is a decision to
revisit, together with CSRF, which is disabled today for the same reason.

## Why preflights work on protected routes

A browser sends a preflight — `OPTIONS` with `Origin` and `Access-Control-Request-Method` — before any "non-simple"
request (any `PUT`/`DELETE`, any `Authorization` header, a JSON body). **It never carries a token.** If the chain asked
for authentication first, every protected route would answer the preflight `401` and the real request would never be
sent.

`http.cors(...)` registers Spring's `CorsFilter` ahead of every authentication filter. For a preflight it answers
`200` with the policy headers and **ends the chain there**, so:

- no `OPTIONS` rule is needed in `authorizeHttpRequests`, on a public route or a protected one;
- the preflight never reaches `JwtAuthenticationFilter`, so a stale token on it cannot become a `401`
  (`CorsIntegrationTest` pins this, because it would otherwise break only for a client holding an old token — see
  [optional-authentication.md](optional-authentication.md));
- a preflight from an origin that is not listed, for a method that is not allowed, or asking for a header that is not
  allowed is answered `403` with no `Access-Control-Allow-Origin`, which the browser reports as a CORS failure.

Because the filter runs first, **error responses carry the CORS headers too**: a `401` or a `429` is readable by the
script instead of surfacing as an opaque network error.

A request with no `Origin` header (curl, the MCP client, server to server) is not a cross-origin browser call and passes
through untouched.

## `OPTIONS` is not in `openapi.yaml`

The contract describes the operations of the API, and a preflight is not one: it is answered by the filter chain and
never reaches a controller. Adding `OPTIONS` to every path would make the YAML roughly twice as long to say the same
thing, and `OpenApiSecurityConformanceTest` would then have to exempt them. The policy lives here and in `CorsConfig`
instead. Nothing in the contract changes for the front end.

## How to add an origin

Set `CORS_ALLOWED_ORIGINS` — in the environment, or in your local `.env` (see [.env.example](../.env.example)) — to a
comma-separated list of origins, each written exactly as a browser sends it:

```properties
CORS_ALLOWED_ORIGINS=http://localhost:5173,https://recettes.example.com
```

No quotes (the `.env` file is parsed as `.properties`), no path, no trailing slash, no wildcard. Restart the API and
check the `CORS allowed origins` line in the log.

To change the method, header or exposed-header lists, edit `CorsConfig`: they are part of the API's behaviour, not
deployment settings, so they are not configurable per environment.

## Tests

`CorsIntegrationTest` (a real `@SpringBootTest` on a random port — a `standaloneSetup` MockMvc has no filter chain, so it
cannot see any of this) covers: preflight on a public, a protected and an admin-only route; a preflight carrying a stale
token; refusal of an unlisted origin, method and header; the exposed headers on a real request and on an error response;
and a request with no `Origin`. `CorsPropertiesTest` covers the startup validation. The allowed origin is pinned in the
test so it passes whatever `CORS_ALLOWED_ORIGINS` is in the developer's `.env`.
