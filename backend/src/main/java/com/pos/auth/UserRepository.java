package com.pos.auth;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"branch", "role", "role.permissions"})
    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = {"branch", "role", "role.permissions"})
    Optional<User> findWithBranchById(Long id);

    @EntityGraph(attributePaths = {"branch", "role", "role.permissions"})
    List<User> findAllByOrderByUsernameAsc();

    long countByRoleId(Long roleId);

    long countByRoleSuperAdminTrueAndActiveTrue();

    boolean existsByUsername(String username);
}
