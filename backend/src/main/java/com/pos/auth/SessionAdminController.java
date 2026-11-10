package com.pos.auth;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.ApiException;

/** Everyone's signed-in sessions: only the super admin sees them and can end any of them. */
@RestController
public class SessionAdminController {

    private final SessionService sessions;

    public SessionAdminController(SessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping("/sessions")
    public List<SessionService.SessionDto> all(@AuthenticationPrincipal AuthUser me) {
        requireSuperAdmin(me);
        return sessions.all(me);
    }

    @GetMapping("/users/{id}/sessions")
    public List<SessionService.SessionDto> ofUser(@PathVariable Long id, @AuthenticationPrincipal AuthUser me) {
        requireSuperAdmin(me);
        return sessions.ofUser(id, me);
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@PathVariable String id, @AuthenticationPrincipal AuthUser me) {
        requireSuperAdmin(me);
        sessions.endAny(id, me);
    }

    /** Not a permission a role can be given: only the built-in super admin role has it. */
    private static void requireSuperAdmin(AuthUser me) {
        if (me == null || !me.role().superAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the super admin can see and end other people's sessions");
        }
    }
}
