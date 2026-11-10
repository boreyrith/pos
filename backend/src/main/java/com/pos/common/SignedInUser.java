package com.pos.common;

/**
 * Who is signed in, for modules below the one that signs people in (the audit log). The request's
 * principal ({@code auth.AuthUser}) implements it.
 */
public interface SignedInUser {

    Long id();

    String username();

    Long branchId();
}
