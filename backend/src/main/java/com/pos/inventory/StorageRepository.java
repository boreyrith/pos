package com.pos.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageRepository extends JpaRepository<Storage, Long> {

    @EntityGraph(attributePaths = "branch")
    List<Storage> findAllByOrderByBranchNameAscSellingDescNameAsc();

    List<Storage> findByBranchId(Long branchId);

    @EntityGraph(attributePaths = "branch")
    Optional<Storage> findByBranchIdAndSellingTrue(Long branchId);

    @EntityGraph(attributePaths = "branch")
    Optional<Storage> findWithBranchById(Long id);
}
