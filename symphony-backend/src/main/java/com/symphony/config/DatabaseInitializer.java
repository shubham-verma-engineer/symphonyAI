package com.symphony.config;

import com.symphony.entity.User;
import com.symphony.entity.Workspace;
import com.symphony.entity.WorkspaceMember;
import com.symphony.repository.UserRepository;
import com.symphony.repository.WorkspaceMemberRepository;
import com.symphony.repository.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceMemberRepository memberRepository;

    public static final UUID DEFAULT_WORKSPACE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEFAULT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Override
    public void run(String... args) {
        log.info("Checking database initialization...");

        if (workspaceRepository.findById(DEFAULT_WORKSPACE_ID).isEmpty()) {
            log.info("Creating default workspace...");
            Workspace ws = Workspace.builder()
                    .id(DEFAULT_WORKSPACE_ID)
                    .name("Default Enterprise Workspace")
                    .billingTier("FREE")
                    .build();
            workspaceRepository.save(ws);
        }

        if (userRepository.findById(DEFAULT_USER_ID).isEmpty()) {
            log.info("Creating default developer/admin user...");
            User user = User.builder()
                    .id(DEFAULT_USER_ID)
                    .username("shubham")
                    .passwordHash("$2a$10$xyz") // dummy hash
                    .email("shubham@enterprise.com")
                    .roleFlag("CREATOR")
                    .build();
            userRepository.save(user);
        }

        WorkspaceMember.WorkspaceMemberId memberId = new WorkspaceMember.WorkspaceMemberId(DEFAULT_WORKSPACE_ID, DEFAULT_USER_ID);
        if (memberRepository.findById(memberId).isEmpty()) {
            log.info("Associating default user to default workspace...");
            WorkspaceMember member = WorkspaceMember.builder()
                    .workspaceId(DEFAULT_WORKSPACE_ID)
                    .userId(DEFAULT_USER_ID)
                    .role("OWNER")
                    .build();
            memberRepository.save(member);
        }

        log.info("Database initialization check complete.");
    }
}
