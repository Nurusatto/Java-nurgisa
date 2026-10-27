package com.fixflow.notification;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> body) {
    return notifications.create(body);
  }

  @PatchMapping("/{id}/read")
  public void read(@PathVariable UUID id) {
    notifications.read(id);
  }

  @GetMapping("/unread-count")
  public Map<String, Object> unread() {
    return notifications.unread();
  }
}
