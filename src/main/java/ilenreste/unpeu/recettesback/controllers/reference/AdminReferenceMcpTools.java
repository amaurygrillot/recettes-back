package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.models.reference.requests.CategoryRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.IngredientRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.TagRequest;
import ilenreste.unpeu.recettesback.models.reference.requests.UnitRequest;
import ilenreste.unpeu.recettesback.models.reference.responses.CategoryResponse;
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
import org.springframework.stereotype.Component;

/**
 * The curation half of the reference data: creating, renaming and deleting categories, tags and units, and renaming
 * or deleting ingredients. Over REST these are admin-only URL rules; here the whole {@code /mcp} URL is admin-only (see
 * {@code SecurityFilterConfig}), so no tool checks the role itself. The services do not check roles either, so these
 * tools must never be reachable by a non-admin: do not relax the {@code /mcp} rule without adding the check back.
 * <p>
 * Everything else — 404 for an unknown id, 409 when a category, tag, unit or ingredient is still in use — is the
 * services' own and reaches the client as a tool error.
 */
@Log4j2
@Component
public class AdminReferenceMcpTools {

    private static final String IN_USE = " Refused if any recipe still uses it.";

    private final CategoryService categoryService;
    private final TagService tagService;
    private final UnitService unitService;
    private final IngredientService ingredientService;
    private final RequestValidator requestValidator;

    public AdminReferenceMcpTools(CategoryService categoryService, TagService tagService, UnitService unitService,
                                  IngredientService ingredientService, RequestValidator requestValidator) {
        this.categoryService = categoryService;
        this.tagService = tagService;
        this.unitService = unitService;
        this.ingredientService = ingredientService;
        this.requestValidator = requestValidator;
    }

    @McpTool(name = "create_category", description = "Create a category, optionally under a parent.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public CategoryResponse createCategory(@McpToolParam(description = "The category to create") CategoryRequest category) {
        log.info("MCP create_category");
        return categoryService.createCategory(requestValidator.validated(category));
    }

    @McpTool(name = "update_category",
            description = "Replace a category's name and parent; an absent parentCategoryId makes it a root.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public CategoryResponse updateCategory(@McpToolParam(description = "The category id") String id,
                                           @McpToolParam(description = "The new name and parent") CategoryRequest category) {
        log.info("MCP update_category {}", id);
        return categoryService.updateCategory(id, requestValidator.validated(category));
    }

    @McpTool(name = "delete_category",
            description = "Delete a category." + IN_USE + " Also refused while it has subcategories.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true, openWorldHint = false))
    public String deleteCategory(@McpToolParam(description = "The category id") String id) {
        log.info("MCP delete_category {}", id);
        categoryService.delete(id);
        return "Category " + id + " deleted";
    }

    @McpTool(name = "create_tag", description = "Create a tag.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public TagResponse createTag(@McpToolParam(description = "The tag to create") TagRequest tag) {
        log.info("MCP create_tag");
        return tagService.createTag(requestValidator.validated(tag));
    }

    @McpTool(name = "update_tag", description = "Rename a tag.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public TagResponse updateTag(@McpToolParam(description = "The tag id") String id,
                                 @McpToolParam(description = "The new name") TagRequest tag) {
        log.info("MCP update_tag {}", id);
        return tagService.updateTag(id, requestValidator.validated(tag));
    }

    @McpTool(name = "delete_tag", description = "Delete a tag." + IN_USE,
            annotations = @McpTool.McpAnnotations(destructiveHint = true, openWorldHint = false))
    public String deleteTag(@McpToolParam(description = "The tag id") String id) {
        log.info("MCP delete_tag {}", id);
        tagService.delete(id);
        return "Tag " + id + " deleted";
    }

    @McpTool(name = "create_unit", description = "Create a measurement unit.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public UnitResponse createUnit(@McpToolParam(description = "The unit to create") UnitRequest unit) {
        log.info("MCP create_unit");
        return unitService.createUnit(requestValidator.validated(unit));
    }

    @McpTool(name = "update_unit", description = "Replace a unit's name and abbreviation.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public UnitResponse updateUnit(@McpToolParam(description = "The unit id") String id,
                                   @McpToolParam(description = "The new name and abbreviation") UnitRequest unit) {
        log.info("MCP update_unit {}", id);
        return unitService.updateUnit(id, requestValidator.validated(unit));
    }

    @McpTool(name = "delete_unit", description = "Delete a unit." + IN_USE,
            annotations = @McpTool.McpAnnotations(destructiveHint = true, openWorldHint = false))
    public String deleteUnit(@McpToolParam(description = "The unit id") String id) {
        log.info("MCP delete_unit {}", id);
        unitService.delete(id);
        return "Unit " + id + " deleted";
    }

    @McpTool(name = "update_ingredient",
            description = "Rename an ingredient or change its icon. A shared row: every recipe using "
                    + "it changes with it.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public IngredientResponse updateIngredient(@McpToolParam(description = "The ingredient id") String id,
                                               @McpToolParam(description = "The new name and icon") IngredientRequest ingredient) {
        log.info("MCP update_ingredient {}", id);
        return ingredientService.updateIngredient(id, requestValidator.validated(ingredient));
    }

    @McpTool(name = "delete_ingredient", description = "Delete an ingredient." + IN_USE,
            annotations = @McpTool.McpAnnotations(destructiveHint = true, openWorldHint = false))
    public String deleteIngredient(@McpToolParam(description = "The ingredient id") String id) {
        log.info("MCP delete_ingredient {}", id);
        ingredientService.delete(id);
        return "Ingredient " + id + " deleted";
    }
}
