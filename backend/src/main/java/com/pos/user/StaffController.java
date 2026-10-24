package com.pos.user;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.UserRepository;

/** Names of active staff, for assigning who did a service. Open to every signed-in user. */
@RestController
public class StaffController {

    public record StaffMember(Long id, String fullName) {
    }

    private final UserRepository users;

    public StaffController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/staff")
    public List<StaffMember> activeStaff() {
        return users.findAll(Sort.by("fullName")).stream()
                .filter(u -> u.isActive())
                .map(u -> new StaffMember(u.getId(), u.getFullName()))
                .toList();
    }
}
