package com.ieee.evaluator.config;

import com.ieee.evaluator.security.JwtAuthenticationFilter;
import com.ieee.evaluator.security.SupabaseJwtValidator;
import com.ieee.evaluator.service.AuthAllowlistService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SupabaseJwtValidator jwtValidator;
    private final AuthAllowlistService allowlistService;

    public SecurityConfig(SupabaseJwtValidator jwtValidator, AuthAllowlistService allowlistService) {
        this.jwtValidator = jwtValidator;
        this.allowlistService = allowlistService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/ai/**").permitAll()
                .requestMatchers("/error").permitAll()
                
                // SyncTrace write/mutating endpoints - teacher role only
                // Goals: POST (create), PUT (update), DELETE
                .requestMatchers(HttpMethod.POST, "/api/synctrace/goals").hasRole("TEACHER")
                .requestMatchers(HttpMethod.PUT, "/api/synctrace/goals/*").hasRole("TEACHER")
                .requestMatchers(HttpMethod.DELETE, "/api/synctrace/goals/*").hasRole("TEACHER")
                // Goal components: POST (add), DELETE (remove)
                .requestMatchers(HttpMethod.POST, "/api/synctrace/goals/*/components").hasRole("TEACHER")
                .requestMatchers(HttpMethod.DELETE, "/api/synctrace/goals/*/components/*").hasRole("TEACHER")
                // Components: POST (create), PUT (rename), DELETE
                .requestMatchers(HttpMethod.POST, "/api/synctrace/components").hasRole("TEACHER")
                .requestMatchers(HttpMethod.PUT, "/api/synctrace/components/*").hasRole("TEACHER")
                .requestMatchers(HttpMethod.DELETE, "/api/synctrace/components/*").hasRole("TEACHER")
                // GitHub ingestion: POST
                .requestMatchers(HttpMethod.POST, "/api/synctrace/github/ingest").hasRole("TEACHER")
                // Continuity triggers: POST
                .requestMatchers(HttpMethod.POST, "/api/synctrace/continuity/align").hasRole("TEACHER")
                .requestMatchers(HttpMethod.POST, "/api/synctrace/continuity/detect-gaps").hasRole("TEACHER")
                .requestMatchers(HttpMethod.POST, "/api/synctrace/continuity/recommendations").hasRole("TEACHER")
                // Proposal analysis: POST
                .requestMatchers(HttpMethod.POST, "/api/synctrace/proposals/extract-goals").hasRole("TEACHER")
                // Audit export: GET (but it's a mutating operation from business perspective)
                .requestMatchers(HttpMethod.GET, "/api/synctrace/audit/*/export").hasRole("TEACHER")
                
                // SyncTrace endpoints - require authentication for all (read-only operations)
                .requestMatchers("/api/synctrace/**").authenticated()
                
                // All other endpoints - permit for now (non-SyncTrace controllers untouched per requirements)
                .anyRequest().permitAll()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtValidator, allowlistService), 
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
