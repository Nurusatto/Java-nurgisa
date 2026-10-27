package com.fixflow.security;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccounts extends JpaRepository<UserAccount, UUID> {
  Optional<UserAccount> findByEmail(String email);
}
