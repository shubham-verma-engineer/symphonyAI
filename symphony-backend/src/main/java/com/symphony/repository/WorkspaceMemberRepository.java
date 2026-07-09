package com.symphony.repository;

import com.symphony.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, WorkspaceMember.WorkspaceMemberId> {
    List<WorkspaceMember> findAllByUserId(UUID userId);
    List<WorkspaceMember> findAllByWorkspaceId(UUID workspaceId);
}
