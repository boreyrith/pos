package com.pos.access;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByCode(String code);

    List<Role> findAllByOrderByBuiltInDescIdAsc();

    /** Users per role, as [roleId, count]. */
    @Query("SELECT u.role.id, COUNT(u) FROM User u GROUP BY u.role.id")
    List<Object[]> countUsers();
}
