package com.pos.shop;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.access.Permission;
import com.pos.access.RoleAccess;
import com.pos.access.RoleRepository;
import com.pos.auth.AuthUser;
import com.pos.auth.User;
import com.pos.auth.UserRepository;
import com.pos.branch.BranchRepository;

/**
 * Who places online orders: the built-in "Online shop" user, so an online sale is an ordinary sale
 * (receipts, reports and stock history show it as the cashier). It is made the first time it is
 * needed, disabled, with a password nobody knows, so nobody can sign in as it. It acts with only
 * the selling permission, at the branch the order is for.
 */
@Component
class ShopActor {

    static final String USERNAME = "online-shop";
    private static final RoleAccess ROLE = new RoleAccess(null, "ONLINE_SHOP", "Online shop", false,
            Set.of(Permission.SALES_SELL), BigDecimal.ZERO);

    private final UserRepository users;
    private final RoleRepository roles;
    private final BranchRepository branches;
    private final PasswordEncoder encoder;
    private final TransactionTemplate newTx;
    private volatile Long userId;

    ShopActor(UserRepository users, RoleRepository roles, BranchRepository branches, PasswordEncoder encoder,
              PlatformTransactionManager txManager) {
        this.users = users;
        this.roles = roles;
        this.branches = branches;
        this.encoder = encoder;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The online shop acting at {@code branchId}. */
    AuthUser at(Long branchId) {
        return new AuthUser(userId(), USERNAME, "Online shop", ROLE, branchId, false, null);
    }

    Long userId() {
        Long id = userId;
        if (id == null) {
            synchronized (this) {
                if (userId == null) {
                    userId = newTx.execute(s -> users.findByUsername(USERNAME).orElseGet(this::create).getId());
                }
                id = userId;
            }
        }
        return id;
    }

    private User create() {
        var branch = branches.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Set up a branch before the online shop takes orders"));
        User u = new User(USERNAME, encoder.encode(UUID.randomUUID().toString()), "Online shop",
                roles.findByCode("CASHIER").orElseThrow(), branch);
        u.setActive(false);
        return users.saveAndFlush(u);
    }
}
