package com.culberth.tools.artemislab.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * No login — the lab is loopback-only ({@code LoopbackOnlyGuard}) — but CSRF protection stays on. Every change is a
 * POST, and without a token check any other page open in this machine's browser could post to the lab and fill a
 * broker. Thymeleaf adds the token to {@code th:action} forms without being asked.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig
{

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception
    {
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    /** None: stops Boot generating a user and printing its password to the log. */
    @Bean
    UserDetailsService userDetailsService()
    {
        return new InMemoryUserDetailsManager();
    }
}
