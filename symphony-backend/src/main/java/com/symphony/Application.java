package com.symphony;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code UserDetailsServiceAutoConfiguration} is excluded because it creates a
 * default in-memory account and logs a generated password on every boot. The
 * account is unreachable — the filter chain configures neither HTTP Basic nor
 * form login — but the banner invites the reader of a security-sensitive startup
 * log to believe a default credential is live. Authentication is handled by
 * {@code AuthController} and {@code JwtAuthenticationFilter}.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
