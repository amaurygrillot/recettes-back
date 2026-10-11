# Which database the tests use

**Status: open problem, accepted for now (decided 2026-10-11).** The test suite writes to the same PostgreSQL as
local development. The goal is a database the tests own and may throw away; this document records why we are not there
yet and what has to be studied first.

## Current situation

There is no test profile and no `src/test/resources`. Every `@SpringBootTest` and `@DataJpaTest` therefore reads
`spring.datasource.url` from [application.properties](../src/main/resources/application.properties):
`localhost:5432/postgres`, the development database.

Consequences:

- `@SpringBootTest(webEnvironment = RANDOM_PORT)` starts a real embedded Tomcat, and what it handles is really
  committed. No transaction wraps a real HTTP call, so there is nothing to roll back.
- Tests that create rows therefore clean up after themselves: `TestAccount`, and the `@AfterEach` of
  `UserCreationIntegrationTest`. Unique names (`test-xxxxxxxx`, `create-xxxxxxxx`) keep runs from colliding with each
  other or with real data.
- That cleanup is a safety net, not isolation. A run killed between the insert and the `@AfterEach` leaves a stray
  account behind, and `ReferenceDataSeeder` writes reference data into the development database on every context
  start.
- `ddl-auto=update` also runs against it, so a test run can alter the development schema.

## Why it is this way

There is no Docker on the development machine, so Testcontainers is not an option, and nothing better was set up in its
place. It was never a deliberate "tests should hit the real database" decision.

## What we want

A database the tests can freely write to and discard. Before choosing, one distinction matters, because it is the
reason a plain mock is not enough here.

**A mocked repository cannot replace the database.** `UserServiceTest` mocks every repository and passed while
`POST /users/create` failed on every call: the bug only existed when Hibernate flushed a dirty `@ManyToMany`
collection at commit, which a mock never does (see [service-controller-contract.md](service-controller-contract.md)
for the same lesson on `@Query`). Mocks stay right for logic *above* the repositories; the persistence layer needs a
real engine.

What can be isolated is **which instance** that engine runs on. Candidates, to be studied:

| Option | For | Against |
|---|---|---|
| Second database on the same local PostgreSQL (`recettes_test`), selected by `src/test/resources/application.properties` | Same engine, so same types, constraints and JPQL behaviour; no new dependency | Created by hand once per machine; CI needs the same step |
| Testcontainers (throwaway PostgreSQL per run) | Fully isolated and reproducible, no manual setup | Needs Docker, which this machine does not have |
| H2 in PostgreSQL mode | Nothing to install, in-memory | Not PostgreSQL: constraint, type and JPQL differences can hide exactly the class of bug above |

Leaning towards the second database for now, moving to Testcontainers if Docker becomes available. H2 is the option to
avoid for the persistence tests.

## To study when this is picked up

- Whether `ReferenceDataSeeder` should run against the test database (it must: tests assume the `USER` role exists).
- How a fresh test database gets its schema (`ddl-auto=update` on first run is enough today; there are no migrations).
- Whether `@DataJpaTest` classes can instead roll back, and `@SpringBootTest` ones keep manual cleanup.
- CI: how the database is provisioned there.
- Update `CLAUDE.md` (Testing and Local database sections) once the decision is made.
