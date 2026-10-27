package com.fixflow.seed;

import static com.fixflow.common.Database.fields;

import com.fixflow.common.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev & !prod")
@ConditionalOnProperty(name = "fixflow.demo-enabled", havingValue = "true")
@Order(1)
public class DevIdentityBootstrap implements CommandLineRunner {
  private final Database db;
  private final PasswordEncoder passwords;
  private final DomainTime time;
  private final String password;

  public DevIdentityBootstrap(
      Database db,
      PasswordEncoder passwords,
      DomainTime time,
      @Value("${FIXFLOW_DEMO_PASSWORD:}") String password) {
    this.db = db;
    this.passwords = passwords;
    this.time = time;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(String... args) {
    db.rows("select pg_advisory_xact_lock(7349821)");
    if (!db.rows("select dataset_version from seed_run where dataset_version='identities-v1'")
        .isEmpty()) return;
    if (password.length() < 10
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalStateException(
          "Set FIXFLOW_DEMO_PASSWORD (at least 10 characters, at most 72 UTF-8 bytes) for explicit"
              + " dev seeding");
    create("owner", "Владелец мастерской", "OWNER");
    for (int i = 1; i <= 2; i++) create("manager" + i, "Администратор " + i, "MANAGER");
    for (int i = 1; i <= 5; i++) create("tech" + i, "Мастер " + i, "TECHNICIAN");
    db.execute(
        "insert into seed_run(dataset_version,base_time,created_at) values ('identities-v1',?,?)",
        time.now(),
        time.now());
  }

  private void create(String login, String name, String role) {
    String email = login + "@fixflow.test";
    if (!db.rows("select id from app_user where email=?", email).isEmpty()) return;
    db.insert(
        "app_user",
        fields(
            "id",
            UUID.nameUUIDFromBytes(email.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "email",
            email,
            "full_name",
            name,
            "password_hash",
            passwords.encode(password),
            "role",
            role,
            "created_at",
            time.now().minus(java.time.Duration.ofDays(730)),
            "updated_at",
            time.now().minus(java.time.Duration.ofDays(730))));
  }
}
