package com.pos.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.audit.AuditAction;
import com.pos.audit.AuditService;
import com.pos.auth.AuthDtos.UserDto;
import com.pos.common.ApiException;
import com.pos.common.ImageTypes;

/**
 * Your own picture, from My account: a photo (PNG, JPEG or WebP up to 2 MB, checked by its bytes) or
 * one of the built-in avatars. One at a time; with neither, the app shows your initials.
 */
@Service
public class ProfilePhotoService {

    static final int MAX_BYTES = 2 * 1024 * 1024;

    /** The built-in avatars (also in V35's CHECK and the web app's avatars.ts). */
    static final Set<String> AVATARS = Set.of("fox", "cat", "dog", "panda", "koala", "lion", "tiger", "frog", "owl", "penguin",
            "rabbit", "bear");

    private final UserRepository users;
    private final UserPhotoRepository photos;
    private final AuditService audit;

    public ProfilePhotoService(UserRepository users, UserPhotoRepository photos, AuditService audit) {
        this.users = users;
        this.photos = photos;
        this.audit = audit;
    }

    /** Stores the photo, replacing any earlier one. */
    @Transactional
    public UserDto set(Long userId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("Choose a photo");
        }
        if (bytes.length > MAX_BYTES) {
            throw ApiException.badRequest("The photo must be 2 MB or smaller");
        }
        String type = ImageTypes.sniff(bytes);
        if (type == null) {
            throw ApiException.badRequest("The photo must be a PNG, JPEG or WebP image");
        }
        User user = find(userId);
        UserPhoto photo = photos.findById(userId).orElseGet(() -> new UserPhoto(userId));
        photo.setData(bytes);
        photo.setContentType(type);
        photo.setSizeBytes(bytes.length);
        photo.setUpdatedAt(Instant.now());
        photos.save(photo);
        boolean replaced = user.isHasPhoto();
        user.setHasPhoto(true);
        user.setAvatar(null);
        user.setPhotoVersion(user.getPhotoVersion() + 1);
        audit.log(AuditAction.PROFILE_PHOTO_CHANGED, "User", userId, (replaced ? "Replaced" : "Added")
                + " their photo (" + type + ", " + (bytes.length / 1024) + " KB)");
        return UserDto.from(user);
    }

    /** Picks a built-in avatar, which replaces a photo; null goes back to the initials. */
    @Transactional
    public UserDto chooseAvatar(Long userId, String avatar) {
        if (avatar != null && !AVATARS.contains(avatar)) {
            throw ApiException.badRequest("Pick one of the avatars shown");
        }
        if (avatar == null) {
            return remove(userId);
        }
        User user = find(userId);
        if (avatar.equals(user.getAvatar()) && !user.isHasPhoto()) {
            return UserDto.from(user);
        }
        dropPhoto(user);
        user.setAvatar(avatar);
        audit.log(AuditAction.PROFILE_PHOTO_CHANGED, "User", userId, "Chose the " + avatar + " avatar");
        return UserDto.from(user);
    }

    /** Back to the initials: removes the photo and the avatar. */
    @Transactional
    public UserDto remove(Long userId) {
        User user = find(userId);
        if (user.isHasPhoto() || user.getAvatar() != null) {
            String what = user.isHasPhoto() ? "their photo" : "the " + user.getAvatar() + " avatar";
            dropPhoto(user);
            user.setAvatar(null);
            audit.log(AuditAction.PROFILE_PHOTO_CHANGED, "User", userId, "Removed " + what);
        }
        return UserDto.from(user);
    }

    private void dropPhoto(User user) {
        if (user.isHasPhoto()) {
            photos.deleteById(user.getId());
            user.setHasPhoto(false);
            user.setPhotoVersion(user.getPhotoVersion() + 1);
        }
    }

    /** The photo's bytes and type, or empty if there is none. */
    @Transactional(readOnly = true)
    public Optional<UserPhoto> get(Long userId) {
        return photos.findById(userId).map(p -> {
            p.getData(); // load the lazy bytes inside the transaction
            return p;
        });
    }

    private User find(Long userId) {
        return users.findWithBranchById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }
}
