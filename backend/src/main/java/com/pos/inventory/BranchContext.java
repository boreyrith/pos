package com.pos.inventory;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.pos.auth.AuthUser;
import com.pos.common.ApiException;

/**
 * Works out which branch a request acts for, and what each person may touch. Staff act for their
 * home branch. People with the "All branches" permission act for the branch in the
 * {@code X-Branch-Id} header (the sidebar switcher) and may act on any branch.
 */
@Component
public class BranchContext {

    public static final String HEADER = "X-Branch-Id";

    private final BranchRepository branches;
    private final StorageRepository storages;

    public BranchContext(BranchRepository branches, StorageRepository storages) {
        this.branches = branches;
        this.storages = storages;
    }

    /** The branch this request acts for. */
    public Long currentBranchId(AuthUser user) {
        if (user.allBranches()) {
            String header = currentHeader();
            if (header != null && !header.isBlank()) {
                try {
                    Long id = Long.valueOf(header.trim());
                    if (branches.existsById(id)) {
                        return id;
                    }
                } catch (NumberFormatException ignored) {
                    // fall back to the home branch
                }
            }
        }
        return user.branchId();
    }

    /** The current branch's selling storage, which sales take stock from. */
    @Transactional(readOnly = true)
    public Storage sellingStorage(AuthUser user) {
        Long branchId = currentBranchId(user);
        Storage storage = storages.findByBranchIdAndSellingTrue(branchId)
                .orElseThrow(() -> ApiException.badRequest("This branch has no selling storage. An admin can set one under Inventory > Locations."));
        if (!storage.isActive() || !storage.getBranch().isActive()) {
            throw ApiException.badRequest("This branch's selling storage is not active");
        }
        return storage;
    }

    /** Fails unless the person may change things in the branch: all-branch users anywhere, others at home. */
    public void requireBranch(AuthUser user, Long branchId) {
        if (!user.allBranches() && !branchId.equals(user.branchId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "You can only do this for your own branch");
        }
    }

    /**
     * The branch filter for lists and reports. All-branch users get what they asked for (null means
     * all branches); everyone else always gets their own branch.
     */
    public Long viewScope(AuthUser user, Long requestedBranchId) {
        return user.allBranches() ? requestedBranchId : user.branchId();
    }

    /** Ids of the active storages in a branch. */
    @Transactional(readOnly = true)
    public List<Long> storageIds(Long branchId) {
        return storages.findByBranchId(branchId).stream().filter(Storage::isActive).map(Storage::getId).toList();
    }

    private static String currentHeader() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getHeader(HEADER);
        }
        return null;
    }
}
