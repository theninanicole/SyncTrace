package com.ieee.evaluator.synctrace.repository;

import com.ieee.evaluator.synctrace.model.GitHubRepositoryLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GitHubRepositoryLinkRepository extends JpaRepository<GitHubRepositoryLink, Long> {

    Optional<GitHubRepositoryLink> findByOwnerAndRepo(String owner, String repo);

    List<GitHubRepositoryLink> findAllByOrderByCreatedAtDesc();
}
