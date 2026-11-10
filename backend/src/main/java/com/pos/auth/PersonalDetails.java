package com.pos.auth;

import java.time.LocalDate;

import com.pos.audit.Changes;
import com.pos.common.ApiException;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * The personal details on a user account, all optional: Khmer and Latin names, gender, date of birth,
 * phone and email. User admins edit them on the account; people edit their own in My account.
 */
public record PersonalDetails(@Size(max = 100) String nameKm, @Size(max = 100) String nameLatin, Gender gender,
                              LocalDate dateOfBirth, @Size(max = 50) String phone, @Email @Size(max = 100) String email) {

    private static final LocalDate EARLIEST_BIRTH = LocalDate.of(1900, 1, 1);

    /** Checks, then sets them on the user and records what changed. */
    public void applyTo(User user, Changes changes, LocalDate today) {
        if (dateOfBirth != null && (dateOfBirth.isBefore(EARLIEST_BIRTH) || !dateOfBirth.isBefore(today))) {
            throw ApiException.badRequest("Enter a date of birth between 1900 and yesterday");
        }
        changes.add("Khmer name", user.getNameKm(), clean(nameKm))
                .add("Latin name", user.getNameLatin(), clean(nameLatin))
                .add("Gender", label(user.getGender()), label(gender))
                .add("Date of birth", str(user.getDateOfBirth()), str(dateOfBirth))
                .add("Email", user.getEmail(), clean(email))
                .add("Phone", user.getPhone(), clean(phone));
        user.setNameKm(clean(nameKm));
        user.setNameLatin(clean(nameLatin));
        user.setGender(gender);
        user.setDateOfBirth(dateOfBirth);
        user.setEmail(clean(email));
        user.setPhone(clean(phone));
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String label(Gender g) {
        return g == null ? null : g == Gender.MALE ? "male" : "female";
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }
}
