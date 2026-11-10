package com.pos.auth;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.audit.Changes;
import com.pos.auth.AuthDtos.UserDto;
import com.pos.common.ApiException;
import com.pos.common.StoreTimeZone;

/**
 * People editing their own personal details in My account. The display name, username, role and
 * branch are not among them: those stay with user admins (the display name is on receipts and in
 * history).
 */
@Service
public class MyDetailsService {

    private final UserRepository users;
    private final AuditService audit;
    private final StoreTimeZone storeTime;

    public MyDetailsService(UserRepository users, AuditService audit, StoreTimeZone storeTime) {
        this.users = users;
        this.audit = audit;
        this.storeTime = storeTime;
    }

    @Transactional
    public UserDto update(Long userId, PersonalDetails details) {
        User user = users.findWithBranchById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Changes changes = new Changes();
        details.applyTo(user, changes, LocalDate.now(storeTime.timezone()));
        if (!changes.isEmpty()) {
            audit.log(AuditAction.USER_UPDATED, "User", userId, user.getUsername() + " updated their own details: "
                    + changes.describe(), changes);
        }
        return UserDto.from(user);
    }
}
