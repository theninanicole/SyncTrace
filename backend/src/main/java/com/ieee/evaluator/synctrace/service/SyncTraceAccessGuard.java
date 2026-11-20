package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.security.AuthenticatedUser;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class SyncTraceAccessGuard {

    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String message) {
            super(message);
        }
    }

    private AuthenticatedUser currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AccessDeniedException("No authenticated SyncTrace user in context.");
        }
        return user;
    }

    public boolean isStudent() {
        return currentUser().isStudent();
    }

    public String currentUserEmail() {
        return currentUser().email();
    }

    /** The Supabase user id of the caller, or null when the token carried none. */
    public String currentUserId() {
        return currentUser().userId();
    }

    /** Teacher-only endpoints (validation metrics) call this first; others get a 403. */
    public void requireTeacher() {
        if (!currentUser().isTeacher()) {
            throw new AccessDeniedException("Only teachers can view validation metrics.");
        }
    }

    public String resolveEffectiveTeamCode(String requestedTeamCode) {
        AuthenticatedUser user = currentUser();

        if (user.isTeacher()) {
            return requestedTeamCode;
        }

        if (user.isStudent()) {
            if (user.groupCode() == null || user.groupCode().isBlank() || "N/A".equalsIgnoreCase(user.groupCode())) {
                throw new AccessDeniedException("No team is on file for this student.");
            }
            return user.groupCode();
        }

        throw new AccessDeniedException("Unrecognized role.");
    }
}
