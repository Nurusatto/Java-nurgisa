package com.fixflow.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class UserAccount {
  @Id private UUID id;
  private String email;

  @Column(name = "full_name")
  private String fullName;

  @Column(name = "password_hash")
  private String passwordHash;

  private String role;
  private boolean active;
  @Version private long version;
  private Instant createdAt;
  private Instant updatedAt;

  protected UserAccount() {}

  public UUID id() {
    return id;
  }

  public String email() {
    return email;
  }

  public String fullName() {
    return fullName;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public String role() {
    return role;
  }

  public boolean active() {
    return active;
  }
}
