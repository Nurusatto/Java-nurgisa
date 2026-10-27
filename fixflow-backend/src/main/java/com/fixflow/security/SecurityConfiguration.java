package com.fixflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixflow.audit.Audit;
import com.fixflow.contracts.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  UserDetailsService userDetailsService(UserAccounts accounts) {
    return email -> {
      var account =
          accounts
              .findByEmail(email.toLowerCase(Locale.ROOT))
              .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
      return User.withUsername(account.email())
          .password(account.passwordHash())
          .roles(account.role())
          .disabled(!account.active())
          .build();
    };
  }

  @Bean
  SecurityFilterChain security(HttpSecurity http, ObjectMapper json, Actors actors, Audit audit)
      throws Exception {
    http.authorizeHttpRequests(
        a ->
            a.requestMatchers(
                    "/actuator/health",
                    "/actuator/health/readiness",
                    "/actuator/health/liveness",
                    "/api/v1/auth/csrf",
                    "/api/v1/public/**")
                .permitAll()
                .anyRequest()
                .authenticated());
    http.formLogin(
        login ->
            login
                .loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email")
                .successHandler(
                    (request, response, auth) -> {
                      audit.record("LOGIN", "user", actors.current().id(), "Вход в систему");
                      response.setContentType("application/json");
                      json.writeValue(response.getOutputStream(), actors.current());
                    })
                .failureHandler(
                    (request, response, error) -> {
                      audit.failedLogin();
                      writeError(
                          json,
                          response,
                          401,
                          "INVALID_CREDENTIALS",
                          "Неверный email или пароль",
                          request.getRequestURI());
                    }));
    http.logout(
        logout ->
            logout
                .logoutUrl("/api/v1/auth/logout")
                .addLogoutHandler(
                    (request, response, auth) -> {
                      if (auth != null)
                        audit.record("LOGOUT", "user", actors.current().id(), "Выход из системы");
                    })
                .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)));
    http.exceptionHandling(
        errors ->
            errors
                .authenticationEntryPoint(
                    (request, response, e) ->
                        writeError(
                            json,
                            response,
                            401,
                            "UNAUTHENTICATED",
                            "Войдите в систему",
                            request.getRequestURI()))
                .accessDeniedHandler(
                    (request, response, e) ->
                        writeError(
                            json,
                            response,
                            403,
                            "ACCESS_DENIED",
                            "Недостаточно прав или недействительный CSRF-токен",
                            request.getRequestURI())));
    http.addFilterBefore(new RateLimitFilter(json), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  static void writeError(
      ObjectMapper json,
      HttpServletResponse response,
      int status,
      String code,
      String message,
      String path)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    json.writeValue(
        response.getOutputStream(),
        new ApiError(
            Instant.now(),
            status,
            code,
            message,
            path.startsWith("/api/v1/public/") ? "/api/v1/public/[redacted]" : path,
            Map.of()));
  }

  private static class RateLimitFilter extends OncePerRequestFilter {
    record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    RateLimitFilter(ObjectMapper json) {
      this.json = json;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      String path = request.getRequestURI();
      boolean login = path.equals("/api/v1/auth/login");
      if (login || path.startsWith("/api/v1/public/")) {
        long now = System.currentTimeMillis();
        long duration = login ? 300_000 : 60_000;
        String key = (login ? "login:" : "public:") + request.getRemoteAddr();
        boolean allowed = allow(key, duration, login ? 30 : 120, now);
        if (login) {
          String email =
              Objects.toString(request.getParameter("email"), "").trim().toLowerCase(Locale.ROOT);
          allowed =
              allowed
                  && email.length() <= 254
                  && allow(
                      "account:" + com.fixflow.common.Idempotency.sha256(email), duration, 30, now);
        }
        if (!allowed) {
          response.setHeader("Retry-After", Long.toString(duration / 1000));
          writeError(
              json, response, 429, "RATE_LIMITED", "Слишком много запросов; повторите позже", path);
          return;
        }
      }
      chain.doFilter(request, response);
    }

    private boolean allow(String key, long duration, int limit, long now) {
      if (windows.size() >= 10_000) {
        windows.entrySet().removeIf(e -> now - e.getValue().start() > 300_000);
        if (windows.size() >= 10_000 && !windows.containsKey(key)) return false;
      }
      var window =
          windows.compute(
              key,
              (k, v) ->
                  v == null || now - v.start() > duration
                      ? new Window(now, 1)
                      : new Window(v.start(), v.count() + 1));
      return window.count() <= limit;
    }
  }
}
