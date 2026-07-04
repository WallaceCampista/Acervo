package com.acervo.repository;

import com.acervo.domain.RetrievalMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RetrievalMetricRepository extends JpaRepository<RetrievalMetric, UUID> {
}
