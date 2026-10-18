package ilenreste.unpeu.recettesback.controllers.recipes;

import ilenreste.unpeu.recettesback.models.recipes.requests.CreateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.RecipeSearchRequest;
import ilenreste.unpeu.recettesback.models.recipes.requests.UpdateRecipeRequest;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeResponse;
import ilenreste.unpeu.recettesback.models.recipes.responses.RecipeSummaryResponse;
import ilenreste.unpeu.recettesback.services.recipes.RecipeService;
import ilenreste.unpeu.recettesback.validation.RequestValidator;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The recipes endpoints, offered to MCP clients as tools.
 * <p>
 * Same contract as {@link RecipeController}, deliberately: each tool delegates to
 * {@code RecipeService}, which owns the ownership rule ("you may edit this recipe
 * because you wrote it") and answers with a response record or throws. The MCP
 * server turns a thrown exception into a tool error carrying its message, so this
 * class holds no {@code try/catch} either.
 * <p>
 * The one thing a controller gets for free and a tool does not is {@code @Valid},
 * hence {@link RequestValidator}.
 * <p>
 * Reaching these tools needs a valid JWT <strong>of an administrator</strong>: {@code /mcp} has its own ADMIN rule
 * in {@code SecurityFilterConfig}. See {@code docs/mcp-server.md}.
 */
@Log4j2
@Component
public class RecipeMcpTools {

    private static final int MAX_PAGE_SIZE = 100;

    private final RecipeService recipeService;
    private final RequestValidator requestValidator;

    public RecipeMcpTools(RecipeService recipeService, RequestValidator requestValidator) {
        this.recipeService = recipeService;
        this.requestValidator = requestValidator;
    }

    @McpTool(name = "search_recipes",
            description = "Search the recipe library. Every criterion is optional and they combine with AND; "
                    + "with none it returns the newest recipes. Category filters include subcategories "
                    + "unless includeSubcategories is false.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<RecipeSummaryResponse> searchRecipes(
            @McpToolParam(description = "Criteria; omit every field to list the newest recipes", required = false)
            RecipeSearchRequest criteria,
            @McpToolParam(description = "Zero-based page number, default 0", required = false) Integer page,
            @McpToolParam(description = "Page size, default 20, at most " + MAX_PAGE_SIZE, required = false)
            Integer size) {
        RecipeSearchRequest request = requestValidator.validated(
                criteria == null ? new RecipeSearchRequest(null, null, null, null, null, null, null, null, null, null)
                        : criteria);
        log.debug("MCP search_recipes {}", request);
        return recipeService.search(request, pageOf(page, size)).getContent();
    }

    @McpTool(name = "get_recipe",
            description = "Fetch one recipe in full: introduction, ingredient groups, steps, pictures.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public RecipeResponse getRecipe(@McpToolParam(description = "The recipe id") String id) {
        log.debug("MCP get_recipe {}", id);
        return recipeService.findById(id);
    }

    @McpTool(name = "create_recipe",
            description = "Create a recipe owned by the caller. Category, tag, ingredient, unit and media ids must "
                    + "already exist: look them up with the list_* and search_ingredients tools first.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public RecipeResponse createRecipe(@McpToolParam(description = "The recipe to create") CreateRecipeRequest recipe) {
        log.info("MCP create_recipe");
        return recipeService.create(requestValidator.validated(recipe));
    }

    @McpTool(name = "update_recipe",
            description = "Partially update a recipe the caller wrote (or any, for an admin). An absent field is "
                    + "left alone; a present list replaces the whole list.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public RecipeResponse updateRecipe(
            @McpToolParam(description = "The recipe id") String id,
            @McpToolParam(description = "The fields to change") UpdateRecipeRequest changes) {
        log.info("MCP update_recipe {}", id);
        return recipeService.update(id, requestValidator.validated(changes));
    }

    @McpTool(name = "delete_recipe",
            description = "Permanently delete a recipe the caller wrote (or any, for an admin), with its "
                    + "introduction, steps and ingredient lines. Cannot be undone.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true, openWorldHint = false))
    public String deleteRecipe(@McpToolParam(description = "The recipe id") String id) {
        log.info("MCP delete_recipe {}", id);
        recipeService.delete(id);
        return "Recipe " + id + " deleted";
    }

    /** The caller's page and size, defaulted and capped like {@code spring.data.web.pageable.*} does for REST. */
    static PageRequest pageOf(Integer page, Integer size) {
        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null ? 20 : Math.clamp(size, 1, MAX_PAGE_SIZE);
        return PageRequest.of(pageNumber, pageSize);
    }
}
