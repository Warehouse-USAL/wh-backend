package com.usal.whbackend.config;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private final JwtAuthFilter jwtAuthFilter;
  private final InfraApiKeyFilter infraApiKeyFilter;

  public SecurityConfig(JwtAuthFilter jwtAuthFilter, InfraApiKeyFilter infraApiKeyFilter) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.infraApiKeyFilter = infraApiKeyFilter;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/auth/login")
                    .permitAll()
                    .requestMatchers(
                        "/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/ws/v1/**")
                    .permitAll()
                    // Probes report only UP/DOWN; restock-cron waits on readiness before login.
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/readiness",
                        "/actuator/health/liveness",
                        "/actuator/info")
                    .permitAll()
                    // The file listing is not public like downloads; the controller's
                    // @PreAuthorize narrows it to SUPERADMIN. Authenticating here makes an
                    // anonymous caller 401 (the advice would otherwise turn it into 403).
                    .requestMatchers(HttpMethod.GET, "/api/v1/files")
                    .authenticated()
                    .requestMatchers("/api/v1/files/**")
                    .permitAll()
                    // Called by the bridge, not by a user: InfraApiKeyFilter does the
                    // authenticating.
                    .requestMatchers("/infra/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (req, res, ex) -> {
                      // Written here rather than sendError: the error dispatch cannot produce
                      // a body for Accept: text/event-stream (the console stream asks for it).
                      res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                      res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                      res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                      res.getWriter()
                          .write(
                              "{\"error\":{\"code\":\"UNAUTHORIZED\","
                                  + "\"message\":\"Debe iniciar sesión para continuar.\"}}");
                    }))
        .addFilterBefore(infraApiKeyFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
