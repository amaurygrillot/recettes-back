# Why `/error` must be `permitAll()`

## The bug

A `@Valid @RequestBody` failure on an otherwise-public endpoint (e.g. `POST /auth/reinit-password`
with a blank email) was coming back as an empty `403 Forbidden` instead of the expected `400 Bad
Request`.

## Root cause

`MethodArgumentNotValidException` (what `@Valid` throws) is resolved by Spring's
`DefaultHandlerExceptionResolver`, which reports it via the servlet container's `sendError(400,
...)` mechanism rather than a plain `response.setStatus(400)`. `sendError` makes the container internally **forward the
request to `GET /error`** (Spring Boot's `BasicErrorController`) to render the error body.

That forward re-enters the app as its own request, so it goes back through the Spring Security filter chain —
`FilterChainProxy` logs `Securing GET /error`. Before this fix, `/error` wasn't covered by any `permitAll()` rule, so it
fell through to `.anyRequest().authenticated()`. The forwarded request is anonymous, and since `SecurityFilterConfig`
never configures an
`AuthenticationEntryPoint` (no `.httpBasic()`, `.oauth2ResourceServer()`, etc.), Spring Security's fallback
`Http403ForbiddenEntryPoint` rejects it outright — discarding the real `400` and replacing it with an empty `403`.

Confirmed directly from `logging.level.org.springframework.security=DEBUG`:

```
Resolved [MethodArgumentNotValidException ...]
Completed 400 BAD_REQUEST
Securing GET /error
Set SecurityContextHolder to anonymous SecurityContext
Http403ForbiddenEntryPoint : Pre-authenticated entry point called. Rejecting access
```

This affects **every** `@Valid`-annotated endpoint, not just the password reset routes — it was just never exercised
against the real running app (with its full security filter chain) until now. It does *not* affect the 400s controllers
build by hand with `ResponseEntity.status(400).build()`
(`UserController`/`AuthenticationController`'s `IllegalStateException` catches): those call
`setStatus` directly, with no `sendError` and therefore no forward.

## Fix

`SecurityFilterConfig` now has `.requestMatchers("/error").permitAll()`. No change was needed in
`JwtAuthenticationFilter`: it extends `OncePerRequestFilter`, whose default
`shouldNotFilterErrorDispatch()` already skips `ERROR`-dispatch requests, so it never ran against this forward in the
first place — only Spring Security's own filters (which do apply to `ERROR`
dispatch by default) needed the explicit permit.

`application.properties` also now pins `server.error.include-stacktrace=never` and
`server.error.include-message=never` explicitly, instead of relying on `spring-boot-devtools`'
dev-time defaults (which flip these to `always`) to not leak into a real deployment. Devtools is
`optional`/`runtime`-scoped and excluded from the packaged jar, so this was never actually exposed in production, but
pinning it removes the "assuming devtools stays absent" implicit dependency.

## Testing lesson

The controller unit tests for the reset endpoints (`AuthenticationControllerTest`,
`UserControllerTest`) use `MockMvcBuilders.standaloneSetup(...)`, which has **no security filter chain at all** — they
passed throughout even though this bug was live, because they can't reproduce it. `SecurityErrorHandlingTest`
(`@SpringBootTest(webEnvironment = RANDOM_PORT)` +
`TestRestTemplate`, hitting the real embedded Tomcat) is the regression test for this specific class of bug: anything
involving the actual servlet container's error/forward handling has to run through the real filter chain to be caught.
