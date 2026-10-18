# MCP server

The API doubles as a [Model Context Protocol](https://modelcontextprotocol.io) server, so an AI client (Claude Desktop,
Claude Code, ...) can browse and edit the recipe library. It is `POST /mcp`, in the same Spring Boot process as the REST
API — there is no second deployable.

## Decisions

- **Spring AI's MCP starter** (`spring-ai-starter-mcp-server-webmvc`), not a hand-rolled JSON-RPC controller. The
  protocol (initialize, `tools/list`, `tools/call`, schema generation) is not where this project's value is. The BOM is
  imported in `pom.xml`; nothing else of Spring AI (models, vector stores) is used.
- **Streamable HTTP, `STATELESS`** (`spring.ai.mcp.server.protocol`). Stateless means no server-side MCP session: every
  request stands alone and is authenticated on its own, which is exactly how the REST API already behaves
  (`SessionCreationPolicy.STATELESS`). A stateful session would be lost on every restart for nothing — we use none of the
  features it exists for (server-initiated notifications, sampling). STDIO was rejected: it has no HTTP identity at all.
- **Same JWT, same filter chain, administrators only.** `/mcp` carries its own `hasRole("ADMIN")` rule in
  `SecurityFilterConfig`: anonymous callers get 401, a logged-in non-admin 403, and that includes the read-only tools.
  The MCP server is the owner's tool for managing the library, not a public API, so it is stricter than REST, where
  recipe reads are public. One URL rule is enough because the rule is the same for every tool; it would not be if
  some tools were meant for ordinary users, since they all share `/mcp` and a URL matcher cannot tell them apart. The
  account must hold the `ADMIN` role *when the token is minted*, so grant it (see `app.bootstrap.admin-username`) and
  log in again. Tokens come from `POST /auth/login` and die at each restart (see `RsaKeyConfig`).
- **Tools delegate to the services**, like the controllers, and so inherit every rule unchanged — above all "you may edit
  a recipe because you wrote it", which lives in `RecipeService`. They sit beside the controllers
  (`RecipeMcpTools`, `ReferenceMcpTools`) because they are the same kind of thing: an HTTP-facing adapter.
- **Validation is explicit.** Spring MVC honours `@Valid` on a controller argument; the MCP server calls tool methods
  reflectively and does not. `RequestValidator` runs the same bean-validation constraints, so a tool cannot save what
  `POST /recipes` would answer 400 to. A failure reaches the client as a tool result with `isError: true`.
- **Reference data: lookups and curation.** `list_*`, `search_ingredients` and `create_ingredient` give a client the ids
  `create_recipe` needs; `AdminReferenceMcpTools` creates, renames and deletes categories, tags and units, and renames or
  deletes ingredients. REST guards those with per-method admin matchers; here the blanket `/mcp` rule does it. The
  services do not check roles, so if `/mcp` is ever opened to non-admins the curation tools need a role check first.

## Tools

| Tool | Does | Hints |
|---|---|---|
| `search_recipes` | search with the `RecipeSearchRequest` criteria, paged | read-only |
| `get_recipe` | one recipe in full | read-only |
| `create_recipe` | `CreateRecipeRequest` | |
| `update_recipe` | partial `UpdateRecipeRequest` | idempotent |
| `delete_recipe` | | destructive |
| `list_categories` | taxonomy tree | read-only |
| `list_tags`, `list_units` | | read-only |
| `search_ingredients` | by name prefix, first 100 | read-only |
| `create_ingredient` | | |
| `create_/update_/delete_` `category`, `tag`, `unit`; `update_/delete_ingredient` | admin only (`AdminReferenceMcpTools`) | delete is destructive |

## Schema gotcha: optional fields

The tool input schema is generated from the request records, and the generator treats **every record component as
required** unless it carries `@JsonProperty(required = false)`. Without that, `update_recipe` would demand all eight
fields — a client could not change just a title — and `create_recipe` would demand `tagIds`, `introduction`, etc. So an
optional field on a request record that tools use needs the annotation too (it is a no-op for REST). The annotation does
not replace `@NotBlank`/`@NotEmpty`: those still enforce, and the schema's `required` list covers only the fields
that must be present.

## Connecting

Claude Code (after logging in to get a token):

```bash
claude mcp add --transport http recettes http://localhost:8080/mcp --header "Authorization: Bearer <token>"
```

Sample requests: `src/test/requests/mcp.http`.
