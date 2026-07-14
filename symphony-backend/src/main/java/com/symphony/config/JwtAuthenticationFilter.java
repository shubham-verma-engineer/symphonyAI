package com.symphony.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtils.validateToken(token)) {
                Claims claims = jwtUtils.parseToken(token);
                
                UUID userId = UUID.fromString(claims.getSubject());
                String username = claims.get("name", String.class);
                
                // Roles extraction
                List<?> rolesList = claims.get("roles", List.class);
                String activeRole = "Employee";
                if (rolesList != null && !rolesList.isEmpty()) {
                    activeRole = (String) rolesList.get(0);
                }

                // Workspace ID extraction
                UUID workspaceId = DatabaseInitializer.DEFAULT_WORKSPACE_ID;
                List<?> workspaces = claims.get("assigned_workspaces", List.class);
                if (workspaces != null && !workspaces.isEmpty()) {
                    Map<?, ?> firstWs = (Map<?, ?>) workspaces.get(0);
                    if (firstWs.containsKey("workspace_id")) {
                        workspaceId = UUID.fromString((String) firstWs.get("workspace_id"));
                    }
                }

                List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + activeRole.toUpperCase()));
                SymphonyAuthentication auth = new SymphonyAuthentication(userId, workspaceId, username, activeRole, authorities);
                
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }
}
