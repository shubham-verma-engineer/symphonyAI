package com.symphony.repository;

import com.symphony.entity.Agent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<Agent, UUID> {

    Optional<Agent> findByWorkspaceIdAndName(UUID workspaceId, String name);

    List<Agent> findAllByWorkspaceId(UUID workspaceId);

    @Modifying
    @Transactional
    @Query(value = "UPDATE agents SET description_embedding = CAST(:embedding AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    @Query(value = "SELECT a.*, (1 - (a.description_embedding <=> CAST(:embedding AS vector))) AS similarity " +
                   "FROM agents a WHERE a.workspace_id = :workspaceId AND a.status = 'ACTIVE' " +
                   "ORDER BY a.description_embedding <=> CAST(:embedding AS vector) LIMIT :limit",
           nativeQuery = true)
    List<Object[]> searchAgentsSemantically(@Param("workspaceId") UUID workspaceId,
                                            @Param("embedding") String embedding,
                                            @Param("limit") int limit);
}
