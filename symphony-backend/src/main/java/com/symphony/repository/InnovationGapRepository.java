package com.symphony.repository;

import com.symphony.entity.InnovationGap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InnovationGapRepository extends JpaRepository<InnovationGap, UUID> {
    List<InnovationGap> findAllByWorkspaceIdOrderByCountFrequencyDesc(UUID workspaceId);
    Optional<InnovationGap> findByWorkspaceIdAndTitleIgnoreCase(UUID workspaceId, String title);
}
