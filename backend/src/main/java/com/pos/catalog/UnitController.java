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

import com.pos.catalog.CatalogDtos.UnitDto;
import com.pos.catalog.CatalogDtos.UnitRequest;
import com.pos.catalog.CatalogDtos.UnitUsageDto;
import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.common.ApiException;

import jakarta.validation.Valid;

/** The units of measure list. Renaming a unit relabels it everywhere except past orders. */
@RestController
@RequestMapping("/units")
public class UnitController {

    private final UnitRepository units;
    private final AuditService audit;

    public UnitController(UnitRepository units, AuditService audit) {
        this.units = units;
        this.audit = audit;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<UnitUsageDto> list() {
        return units.findAll(Sort.by("symbol")).stream()
                .map(u -> new UnitUsageDto(u.getId(), u.getSymbol(), u.getName(), units.usageCount(u.getId())))
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    @Transactional
    public UnitDto create(@Valid @RequestBody UnitRequest req) {
        Unit u = units.saveAndFlush(new Unit(req.symbol().trim(), req.name().trim()));
        audit.log(AuditAction.UNIT_CREATED, "Unit", u.getId(), "Added unit " + u.getSymbol() + " (" + u.getName() + ")");
        return UnitDto.from(u);
    }

    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public UnitDto update(@PathVariable Long id, @Valid @RequestBody UnitRequest req) {
        Unit unit = units.findById(id).orElseThrow(() -> ApiException.notFound("Unit"));
        Changes changes = new Changes().add("Symbol", unit.getSymbol(), req.symbol().trim()).add("Name", unit.getName(), req.name().trim());
        if (!changes.isEmpty()) {
            audit.log(AuditAction.UNIT_UPDATED, "Unit", unit.getId(), "Unit " + unit.getSymbol() + ": " + changes.describe(), changes);
        }
        unit.setSymbol(req.symbol().trim());
        unit.setName(req.name().trim());
        units.flush();
        return UnitDto.from(unit);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public void delete(@PathVariable Long id) {
        long usage = units.usageCount(id);
        if (usage > 0) {
            throw ApiException.conflict("This unit is used by " + usage + " catalog "
                    + (usage == 1 ? "entry" : "entries") + ". Change them to another unit first.");
        }
        units.findById(id).ifPresent(u -> audit.log(AuditAction.UNIT_DELETED, "Unit", id, "Deleted unit " + u.getSymbol()));
        units.deleteById(id);
    }
}
