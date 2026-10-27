package com.fixflow.security;

import com.fixflow.common.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Actors {
  public record Actor(UUID id, String email, String name, String role) {
    public boolean owner() {
      return role.equals("OWNER");
    }

    public boolean technician() {
      return role.equals("TECHNICIAN");
    }
  }

  private final Database db;
  private final ThreadLocal<UUID> seedActor = new ThreadLocal<>();

  public Actors(Database db) {
    this.db = db;
  }

  public Actor current() {
    Map<String, Object> row;
    if (seedActor.get() != null) row = db.get("app_user", seedActor.get());
    else {
      var auth = SecurityContextHolder.getContext().getAuthentication();
      if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser"))
        throw new ApiException(401, "UNAUTHENTICATED", "Войдите в систему");
      row = db.one("select * from app_user where email=?", auth.getName());
    }
    if (!Boolean.TRUE.equals(row.get("active"))) throw ApiException.forbidden();
    return new Actor(
        Input.id(row.get("id")),
        row.get("email").toString(),
        row.get("full_name").toString(),
        row.get("role").toString());
  }

  public Actor manager() {
    var actor = current();
    if (actor.technician()) throw ApiException.forbidden();
    return actor;
  }

  public Actor owner() {
    var actor = current();
    if (!actor.owner()) throw ApiException.forbidden();
    return actor;
  }

  public <T> T as(UUID id, Supplier<T> task) {
    var previous = seedActor.get();
    try {
      seedActor.set(id);
      return task.get();
    } finally {
      if (previous == null) seedActor.remove();
      else seedActor.set(previous);
    }
  }
}
