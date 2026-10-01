package com.ieee.evaluator.security;

public record AuthenticatedUser(String email, String role, String groupCode) {
    public boolean isTeacher() {
        return "TEACHER".equalsIgnoreCase(role);
    }

    public boolean isStudent() {
        return "STUDENT".equalsIgnoreCase(role);
    }
}
