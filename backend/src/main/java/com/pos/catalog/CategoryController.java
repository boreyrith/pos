package com.pos.catalog;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.CatalogDtos.CategoryDto;
import com.pos.catalog.CatalogDtos.CategoryRequest;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.common.ApiException;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryRepository categories;
    private final AuditService audit;

    public CategoryController(CategoryRepository categories, AuditService audit) {
        this.categories = categories;
        this.audit = audit;
    }

    @GetMapping
    public List<CategoryDto> list() {
        return categories.findAll(Sort.by("name")).stream().map(CategoryDto::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    @Transactional
    public CategoryDto create(@Valid @RequestBody CategoryRequest req) {
        Category c = categories.saveAndFlush(new Category(req.name().trim()));
        audit.log(AuditAction.CATEGORY_CREATED, "Category", c.getId(), "Added category " + c.getName());
        return CategoryDto.from(c);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public CategoryDto rename(@PathVariable Long id, @Valid @RequestBody CategoryRequest req) {
        Category c = categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
        if (!c.getName().equals(req.name().trim())) {
            audit.log(AuditAction.CATEGORY_UPDATED, "Category", id, "Renamed category " + c.getName() + " → " + req.name().trim());
        }
        c.setName(req.name().trim());
        categories.flush();
        return CategoryDto.from(c);
    }

    /** Entries in a deleted category become uncategorised (ON DELETE SET NULL). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    @Transactional
    public void delete(@PathVariable Long id) {
        categories.findById(id).ifPresent(c -> audit.log(AuditAction.CATEGORY_DELETED, "Category", id, "Deleted category " + c.getName()));
        categories.deleteById(id);
    }
}
