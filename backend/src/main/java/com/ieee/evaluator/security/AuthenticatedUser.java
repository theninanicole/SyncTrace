package com.ieee.evaluator.security;

/**
 * @param userId the Supabase user id (JWT {@code sub}); used where records must not carry an
 *               email, such as validation-metric exports. May be null for tokens without one.
 */
public record AuthenticatedUser(String email, String role, String groupCode, String userId) {

    public AuthenticatedUser(String email, String role, String groupCode) {
        this(email, role, groupCode, null);
    }

    public boolean isTeacher() {
        return "TEACHER".equalsIgnoreCase(role);
    }

    public boolean isStudent() {
        return "STUDENT".equalsIgnoreCase(role);
    }
}
