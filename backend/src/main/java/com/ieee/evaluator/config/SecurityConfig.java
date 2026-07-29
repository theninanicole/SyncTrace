package com.ieee.evaluator.config;

import com.ieee.evaluator.security.JwtAuthenticationFilter;
import com.ieee.evaluator.security.SupabaseJwtValidator;
import com.ieee.evaluator.service.AuthAllowlistService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final SupabaseJwtValidator jwtValidator;
    private final AuthAllowlistService allowlistService;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(SupabaseJwtValidator jwtValidator, AuthAllowlistService allowlistService, CorsConfigurationSource corsConfigurationSource) {
        this.jwtValidator = jwtValidator;
        this.allowlistService = allowlistService;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/ai/**").permitAll()
                .requestMatchers("/error").permitAll()
                
                // All other endpoints - permit for now (non-SyncTrace controllers untouched per requirements)
                .anyRequest().permitAll()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtValidator, allowlistService), 
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
