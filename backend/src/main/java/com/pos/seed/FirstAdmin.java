package com.pos.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.access.Role;
import com.pos.access.RoleRepository;
import com.pos.auth.User;
import com.pos.auth.UserRepository;
import com.pos.inventory.BranchRepository;

/**
 * A new installation (outside development) has no accounts. If ADMIN_USERNAME and ADMIN_PASSWORD are
 * set, the first start creates that Super Admin at the main branch; they must choose their own
 * password at first sign-in. Once any account exists this does nothing, so the variables can stay.
 */
@Component
@Profile("!dev")
public class FirstAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdmin.class);

    private final UserRepository users;
    private final RoleRepository roles;
    private final BranchRepository branches;
    private final PasswordEncoder passwords;
    private final String username;
    private final String password;

    public FirstAdmin(UserRepository users, RoleRepository roles, BranchRepository branches, PasswordEncoder passwords,
                      @Value("${ADMIN_USERNAME:}") String username, @Value("${ADMIN_PASSWORD:}") String password) {
        this.users = users;
        this.roles = roles;
        this.branches = branches;
        this.passwords = passwords;
        this.username = username.trim();
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (username.isEmpty() || password.length() < 8) {
            log.warn("No accounts yet. Set ADMIN_USERNAME and ADMIN_PASSWORD (at least 8 characters) and restart "
                    + "to create the first administrator.");
            return;
        }
        var main = branches.findAll().stream().filter(b -> b.getCode().equals("MAIN")).findFirst()
                .orElseGet(() -> branches.findAll().getFirst());
        User admin = new User(username, passwords.encode(password), "Administrator",
                roles.findByCode(Role.SUPER_ADMIN).orElseThrow(), main);
        admin.setMustChangePassword(true);
        users.save(admin);
        log.info("Created the first administrator \"{}\". They choose their own password at first sign-in.", username);
    }
}
