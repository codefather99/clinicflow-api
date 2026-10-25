package com.clinicflow.web.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.beans.factory.annotation.Qualifier;

import java.time.Clock;
import java.util.List;

/**
 * Access rules live HERE, in the service, not in the interface (brief requirement). The wrong-role test calls
 * these routes directly over HTTP and must fail if a rule below is deleted.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    JwtService jwtService(@Value("${clinicflow.jwt.secret}") String secret,
                          @Value("${clinicflow.jwt.ttl-minutes:60}") long ttlMinutes) {
        return new JwtService(secret, ttlMinutes * 60, Clock.systemUTC());
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${clinicflow.cors.allowed-origins}") List<String> origins) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(origins);
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id", "X-Manage-Token"));
        c.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, ProblemWriter problems,
                                            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors) throws Exception {
        http
                .csrf(csrf -> csrf.disable())                       // stateless bearer tokens, no cookies
                .cors(c -> c.configurationSource(cors))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(problems::unauthenticated)
                        .accessDeniedHandler(problems::forbidden))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/error").permitAll()
                        // anonymous: the patient side
                        .requestMatchers(HttpMethod.GET, "/api/v1/meta", "/api/v1/visit-types", "/api/v1/slots").permitAll()
                        .requestMatchers("/api/v1/patient/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // admin only
                        .requestMatchers(HttpMethod.POST, "/api/v1/demo/reset").hasRole("ADMIN")
                        // everything else in the API is staff
                        .requestMatchers("/api/v1/**").hasAnyRole("RECEPTIONIST", "ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(new JwtAuthFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
