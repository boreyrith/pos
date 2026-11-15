package com.pos.shop;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface ShopSettingsRepository extends JpaRepository<ShopSettings, Short> {

    @EntityGraph(attributePaths = {"deliveryBranch", "deliveryOffering", "updatedBy"})
    Optional<ShopSettings> findWithDetailsById(Short id);
}
