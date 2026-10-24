package com.pos.catalog;

import java.util.List;

import com.pos.catalog.CatalogDtos.UnitQuantity;
import com.pos.common.ApiException;

/** Turns amounts in an entry's units (base unit or packs) into base units. */
public final class UnitConversion {

    private UnitConversion() {
    }

    /** Total in base units, e.g. 3 carton (200 pcs each) + 5 pcs = 605. Packs must be loadable. */
    public static int toBase(Offering o, List<UnitQuantity> quantities) {
        long total = 0;
        for (UnitQuantity q : quantities) {
            total += (long) q.quantity() * factorOf(o, q.unitId());
        }
        if (total > Integer.MAX_VALUE) {
            throw ApiException.badRequest("That is more stock than can be recorded");
        }
        return (int) total;
    }

    /** How many base units one of {@code unitId} is for this entry. */
    public static int factorOf(Offering o, Long unitId) {
        if (o.getUnit() != null && o.getUnit().getId().equals(unitId)) {
            return 1;
        }
        return o.getPacks().stream().filter(p -> p.getUnit().getId().equals(unitId)).findFirst()
                .map(OfferingPack::getFactor)
                .orElseThrow(() -> ApiException.badRequest("That unit isn't set up for " + o.getName()
                        + ". Add it as a pack first."));
    }
}
