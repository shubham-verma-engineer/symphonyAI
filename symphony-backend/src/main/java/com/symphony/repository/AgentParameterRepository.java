package com.symphony.repository;

import com.symphony.entity.AgentParameter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentParameterRepository extends JpaRepository<AgentParameter, UUID> {
    List<AgentParameter> findAllByAgentId(UUID agentId);
    void deleteAllByAgentId(UUID agentId);
}
