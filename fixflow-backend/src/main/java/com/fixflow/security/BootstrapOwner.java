package com.fixflow.security;

import static com.fixflow.common.Database.fields;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
@ConditionalOnProperty(name = "fixflow.bootstrap-owner.enabled", havingValue = "true")
public class BootstrapOwner implements CommandLineRunner {
  private final Database db;
  private final Actors actors;
  private final Audit audit;
  private final PasswordEncoder passwords;
  private final DomainTime time;
  private final String email;
  private final String password;
  private final String name;

  public BootstrapOwner(
      Database db,
      Actors actors,
      Audit audit,
      PasswordEncoder passwords,
      DomainTime time,
      @Value("${FIXFLOW_BOOTSTRAP_OWNER_EMAIL:}") String email,
      @Value("${FIXFLOW_BOOTSTRAP_OWNER_PASSWORD:}") String password,
      @Value("${FIXFLOW_BOOTSTRAP_OWNER_NAME:Владелец}") String name) {
    this.db = db;
    this.actors = actors;
    this.audit = audit;
    this.passwords = passwords;
    this.time = time;
    this.email = email.trim().toLowerCase(Locale.ROOT);
    this.password = password;
    this.name = name.trim();
  }

  @Override
  @Transactional
  public void run(String... args) {
    db.rows("select pg_advisory_xact_lock(7349821)");
    if (!db.rows("select id from app_user limit 1").isEmpty()) return;
    if (email.length() > 254
        || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || password.isBlank()
        || password.length() < 12
        || password.length() > 100
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || name.isBlank()
        || name.length() > 150)
      throw new IllegalStateException(
          "Provide valid FIXFLOW_BOOTSTRAP_OWNER_EMAIL/NAME and a password with at least 12"
              + " characters and at most 72 UTF-8 bytes for an empty installation");
    UUID owner =
        db.insert(
            "app_user",
            fields(
                "email",
                email,
                "full_name",
                name,
                "password_hash",
                passwords.encode(password),
                "role",
                "OWNER",
                "created_at",
                time.now(),
                "updated_at",
                time.now()));
    actors.as(
        owner,
        () -> {
          audit.record(
              "OWNER_BOOTSTRAPPED",
              "app_user",
              owner,
              "Первый владелец создан при явной инициализации пустой системы");
          return null;
        });
  }
}
