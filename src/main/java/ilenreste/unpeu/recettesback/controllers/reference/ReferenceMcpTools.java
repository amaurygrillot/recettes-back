package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientSearchRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryTreeResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.IngredientResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.TagResponse;
import ilenreste.unpeu.recettesback.models.reference.responses.UnitResponse;
import ilenreste.unpeu.recettesback.services.reference.CategoryService;
import ilenreste.unpeu.recettesback.services.reference.IngredientService;
import ilenreste.unpeu.recettesback.services.reference.TagService;
import ilenreste.unpeu.recettesback.services.reference.UnitService;
import ilenreste.unpeu.recettesback.validation.RequestValidator;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reference data as MCP tools: the lookups a client needs to turn "tarte aux
 * pommes, 200 g de farine" into the ids {@code create_recipe} asks for, plus
 * ingredient creation, which the REST API also leaves to any authenticated user.
 * <p>
 * Categories, tags and units are curated by admins over REST and stay read-only
 * here. Like {@link ilenreste.unpeu.recettesback.controllers.recipes.RecipeMcpTools},
 * every tool delegates to a service and so inherits its rules unchanged.
 */
@Log4j2
@Component
public class ReferenceMcpTools {

    private static final int INGREDIENT_PAGE_SIZE = 100;

    private final CategoryService categoryService;
    private final TagService tagService;
    private final UnitService unitService;
    private final IngredientService ingredientService;
    private final RequestValidator requestValidator;

    public ReferenceMcpTools(CategoryService categoryService, TagService tagService, UnitService unitService,
                             IngredientService ingredientService, RequestValidator requestValidator) {
        this.categoryService = categoryService;
        this.tagService = tagService;
        this.unitService = unitService;
        this.ingredientService = ingredientService;
        this.requestValidator = requestValidator;
    }

    @McpTool(name = "list_categories",
            description = "The whole category taxonomy as a nested tree; use the ids in create_recipe and search_recipes.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<CategoryTreeResponse> listCategories() {
        log.debug("MCP list_categories");
        return categoryService.findHierarchy();
    }

    @McpTool(name = "list_tags", description = "Every tag, with its id.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<TagResponse> listTags() {
        log.debug("MCP list_tags");
        return tagService.list();
    }

    @McpTool(name = "list_units", description = "Every measurement unit, with its id and abbreviation.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<UnitResponse> listUnits() {
        log.debug("MCP list_units");
        return unitService.list();
    }

    @McpTool(name = "search_ingredients",
            description = "Find ingredients by name prefix (accent- and case-insensitive); returns at most "
                    + INGREDIENT_PAGE_SIZE + ", name-ordered. Check here before creating a new ingredient.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, openWorldHint = false))
    public List<IngredientResponse> searchIngredients(
            @McpToolParam(description = "Start of the ingredient name; omit to list from the beginning",
                    required = false) String namePrefix) {
        log.debug("MCP search_ingredients '{}'", namePrefix);
        IngredientSearchRequest request = requestValidator.validated(
                new IngredientSearchRequest(namePrefix, null, null));
        return ingredientService.search(request, PageRequest.of(0, INGREDIENT_PAGE_SIZE)).getContent();
    }

    @McpTool(name = "create_ingredient",
            description = "Add an ingredient that search_ingredients did not find. A duplicate name is rejected.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public IngredientResponse createIngredient(
            @McpToolParam(description = "The ingredient to add") IngredientRequest ingredient) {
        log.info("MCP create_ingredient");
        return ingredientService.createIngredient(requestValidator.validated(ingredient));
    }
}
