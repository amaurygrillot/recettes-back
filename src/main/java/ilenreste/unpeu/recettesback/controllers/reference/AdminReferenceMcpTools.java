package ilenreste.unpeu.recettesback.controllers.reference;

import ilenreste.unpeu.recettesback.exceptions.ForbiddenOperationException;
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
import ilenreste.unpeu.recettesback.services.users.CurrentUserService;
import ilenreste.unpeu.recettesback.validation.RequestValidator;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The admin-only half of the reference data: creating, renaming and deleting categories, tags and units, and renaming
 * or deleting ingredients.
 * <p>
 * Over REST these rules are URL matchers in {@code SecurityFilterConfig}. Every tool shares the single {@code /mcp}
 * URL, so a matcher cannot tell {@code delete_category} from {@code list_tags}; the role is checked here instead, by
 * {@link #requireAdmin()}, before anything is touched. The services themselves do not check roles.
 * <p>
 * Everything else — 404 for an unknown id, 409 when a category, tag, unit or ingredient is still in use — is the
 * services' own and reaches the client as a tool error.
 */
@Log4j2
@Component
public class AdminReferenceMcpTools {

    private static final String ADMIN_ONLY = "Admin only. ";
    private static final String IN_USE = " Refused if any recipe still uses it.";

    private final CategoryService categoryService;
    private final TagService tagService;
    private final UnitService unitService;
    private final IngredientService ingredientService;
    private final CurrentUserService currentUserService;
    private final RequestValidator requestValidator;

    public AdminReferenceMcpTools(CategoryService categoryService, TagService tagService, UnitService unitService,
                                  IngredientService ingredientService, CurrentUserService currentUserService,
                                  RequestValidator requestValidator) {
        this.categoryService = categoryService;
        this.tagService = tagService;
        this.unitService = unitService;
        this.ingredientService = ingredientService;
        this.currentUserService = currentUserService;
        this.requestValidator = requestValidator;
    }

    @McpTool(name = "create_category", description = ADMIN_ONLY + "Create a category, optionally under a parent.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public CategoryResponse createCategory(@McpToolParam(description = "The category to create") CategoryRequest category) {
        requireAdmin();
        log.info("MCP create_category");
        return categoryService.createCategory(requestValidator.validated(category));
    }

    @McpTool(name = "update_category",
            description = ADMIN_ONLY + "Replace a category's name and parent; an absent parentCategoryId makes it a root.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public CategoryResponse updateCategory(@McpToolParam(description = "The category id") String id,
                                           @McpToolParam(description = "The new name and parent") CategoryRequest category) {
        requireAdmin();
        log.info("MCP update_category {}", id);
        return categoryService.updateCategory(id, requestValidator.validated(category));
    }

    @McpTool(name = "delete_category",
            description = ADMIN_ONLY + "Delete a category." + IN_USE + " Also refused while it has subcategories.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public String deleteCategory(@McpToolParam(description = "The category id") String id) {
        requireAdmin();
        log.info("MCP delete_category {}", id);
        categoryService.delete(id);
        return "Category " + id + " deleted";
    }

    @McpTool(name = "create_tag", description = ADMIN_ONLY + "Create a tag.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public TagResponse createTag(@McpToolParam(description = "The tag to create") TagRequest tag) {
        requireAdmin();
        log.info("MCP create_tag");
        return tagService.createTag(requestValidator.validated(tag));
    }

    @McpTool(name = "update_tag", description = ADMIN_ONLY + "Rename a tag.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public TagResponse updateTag(@McpToolParam(description = "The tag id") String id,
                                 @McpToolParam(description = "The new name") TagRequest tag) {
        requireAdmin();
        log.info("MCP update_tag {}", id);
        return tagService.updateTag(id, requestValidator.validated(tag));
    }

    @McpTool(name = "delete_tag", description = ADMIN_ONLY + "Delete a tag." + IN_USE,
            annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public String deleteTag(@McpToolParam(description = "The tag id") String id) {
        requireAdmin();
        log.info("MCP delete_tag {}", id);
        tagService.delete(id);
        return "Tag " + id + " deleted";
    }

    @McpTool(name = "create_unit", description = ADMIN_ONLY + "Create a measurement unit.",
            annotations = @McpTool.McpAnnotations(destructiveHint = false, openWorldHint = false))
    public UnitResponse createUnit(@McpToolParam(description = "The unit to create") UnitRequest unit) {
        requireAdmin();
        log.info("MCP create_unit");
        return unitService.createUnit(requestValidator.validated(unit));
    }

    @McpTool(name = "update_unit", description = ADMIN_ONLY + "Replace a unit's name and abbreviation.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public UnitResponse updateUnit(@McpToolParam(description = "The unit id") String id,
                                   @McpToolParam(description = "The new name and abbreviation") UnitRequest unit) {
        requireAdmin();
        log.info("MCP update_unit {}", id);
        return unitService.updateUnit(id, requestValidator.validated(unit));
    }

    @McpTool(name = "delete_unit", description = ADMIN_ONLY + "Delete a unit." + IN_USE,
            annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public String deleteUnit(@McpToolParam(description = "The unit id") String id) {
        requireAdmin();
        log.info("MCP delete_unit {}", id);
        unitService.delete(id);
        return "Unit " + id + " deleted";
    }

    @McpTool(name = "update_ingredient",
            description = ADMIN_ONLY + "Rename an ingredient or change its icon. A shared row: every recipe using "
                    + "it changes with it.",
            annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = false))
    public IngredientResponse updateIngredient(@McpToolParam(description = "The ingredient id") String id,
                                               @McpToolParam(description = "The new name and icon") IngredientRequest ingredient) {
        requireAdmin();
        log.info("MCP update_ingredient {}", id);
        return ingredientService.updateIngredient(id, requestValidator.validated(ingredient));
    }

    @McpTool(name = "delete_ingredient", description = ADMIN_ONLY + "Delete an ingredient." + IN_USE,
            annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public String deleteIngredient(@McpToolParam(description = "The ingredient id") String id) {
        requireAdmin();
        log.info("MCP delete_ingredient {}", id);
        ingredientService.delete(id);
        return "Ingredient " + id + " deleted";
    }

    private void requireAdmin() {
        if (!currentUserService.isAdmin()) {
            log.warn("MCP admin tool refused: caller is not an admin");
            throw new ForbiddenOperationException("This tool is restricted to administrators");
        }
    }
}
