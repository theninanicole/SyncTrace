package com.ieee.evaluator.synctrace.validation.repository;

import com.ieee.evaluator.synctrace.validation.model.ValidationSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValidationSessionRepository extends JpaRepository<ValidationSession, Long> {
    List<ValidationSession> findAllByOrderByStartsAtDescIdDesc();
}
