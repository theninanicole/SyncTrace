package com.ieee.evaluator.synctrace.service;

import com.ieee.evaluator.security.AuthenticatedUser;
import com.ieee.evaluator.synctrace.repository.TraceabilityResultPublicationRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class SyncTraceAccessGuard {

    private final TraceabilityResultPublicationRepository publicationRepository;

    public SyncTraceAccessGuard(TraceabilityResultPublicationRepository publicationRepository) {
        this.publicationRepository = publicationRepository;
    }

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

    public void requirePublishedIfStudent(String teamCode) {
        AuthenticatedUser user = currentUser();
        if (!user.isStudent()) {
            return;
        }
        boolean published = publicationRepository.findByTeamCodeIgnoreCase(teamCode)
                .map(publication -> publication.getPublishedAt() != null)
                .orElse(false);
        if (!published) {
            throw new AccessDeniedException("Traceability results have not been published for this team yet.");
        }
    }
}
