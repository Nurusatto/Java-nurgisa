package com.fixflow.security;

import com.fixflow.audit.Audit;
import com.fixflow.common.*;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final Actors actors;
  private final Database db;
  private final PasswordEncoder passwords;
  private final Audit audit;

  public AuthController(Actors actors, Database db, PasswordEncoder passwords, Audit audit) {
    this.actors = actors;
    this.db = db;
    this.passwords = passwords;
    this.audit = audit;
  }

  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
  }

  @GetMapping("/me")
  public Actors.Actor me() {
    return actors.current();
  }

  @PostMapping("/change-password")
  @Transactional
  public void password(@RequestBody Map<String, Object> body) {
    var actor = actors.current();
    var user = db.lock("app_user", actor.id());
    if (!passwords.matches(
        Input.secret(body, "oldPassword", 200, true), user.get("password_hash").toString()))
      throw ApiException.invalid("Текущий пароль неверен");
    String next = Input.secret(body, "newPassword", 100, true);
    if (next.length() < 10)
      throw ApiException.invalid("Пароль должен содержать минимум 10 символов");
    db.execute(
        "update app_user set password_hash=?,version=version+1,updated_at=current_timestamp where"
            + " id=?",
        passwords.encode(next),
        actor.id());
    db.execute("delete from spring_session where principal_name=?", actor.email());
    audit.record("PASSWORD_CHANGED", "user", actor.id(), "Пароль изменён");
  }
}
