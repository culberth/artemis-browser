package com.culberth.tools.artemisbrowser.web;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The tool's own login, which exists because the tool is allowed to be reached from somewhere other than localhost.
 *
 * <p>
 * Everything behind it is read-only, so this is not protecting the broker's data from modification — it is protecting a
 * live, authenticated broker connection from whoever can reach the port. Without a login, making the app
 * network-reachable would hand a read of every queue on the broker to anyone who found it.
 *
 * <p>
 * One account, configured rather than managed: {@code artemis.auth.username} and a bcrypt
 * {@code artemis.auth.password-hash}. There is no sign-up, no password reset and no user list, because a tool one
 * person runs on a jump host does not need them and each would be another thing to get wrong. Start the app with
 * {@code --hash-password=...} to generate the hash.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig
{

    /** Reachable without signing in: the login page itself, the stylesheet it uses, and the error page. */
    private static final List<String> PUBLIC_PATHS = List.of("/login", "/app.css", "/error");

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception
    {
        http.authorizeHttpRequests(requests -> requests.requestMatchers(PUBLIC_PATHS.toArray(new String[0])).permitAll()
                .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true).failureUrl("/login?failed")
                        .permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?signedOut").permitAll())
                // A new session on sign-in, so a session id an attacker planted beforehand is not
                // the one that ends up authenticated.
                .sessionManagement(session -> session.sessionFixation().newSession());
        // CSRF stays on: every form here is a POST that does something (connect, disconnect, forget
        // a saved broker), and Thymeleaf adds the token to th:action forms without being asked.
        return http.build();
    }

    /**
     * The single configured account, or none at all.
     *
     * <p>
     * An unconfigured login means nobody can sign in, rather than everybody — which pairs with
     * {@link ReachabilityGuard} refusing to start network-reachable without one. Spring Boot's own generated password
     * is deliberately not relied on: it changes on every restart and is printed to the log.
     */
    @Bean
    UserDetailsService userDetailsService(@Value("${artemis.auth.username:}") String username,
            @Value("${artemis.auth.password-hash:}") String passwordHash, PasswordEncoder passwordEncoder)
    {
        if (username.isBlank() || passwordHash.isBlank())
        {
            return new InMemoryUserDetailsManager();
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username.trim()).password(normalise(passwordHash)).roles("USER").build());
    }

    @Bean
    PasswordEncoder passwordEncoder()
    {
        return new BCryptPasswordEncoder();
    }

    /**
     * Accepts the hash with or without Spring Security's {@code {bcrypt}} prefix. The prefix is what a delegating
     * encoder wants and what most examples show; the bare hash is what a bcrypt tool prints. Rejecting one of them over
     * a detail like that is a bad first five minutes.
     */
    private String normalise(String passwordHash)
    {
        String trimmed = passwordHash.trim();
        return trimmed.startsWith("{bcrypt}") ? trimmed.substring("{bcrypt}".length()) : trimmed;
    }
}
