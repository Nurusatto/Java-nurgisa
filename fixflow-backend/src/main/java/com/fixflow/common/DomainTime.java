package com.fixflow.common;

import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class DomainTime {
  private final ThreadLocal<Instant> scenarioTime = new ThreadLocal<>();

  public Instant now() {
    return scenarioTime.get() == null ? Instant.now() : scenarioTime.get();
  }

  public <T> T at(Instant instant, Supplier<T> work) {
    var previous = scenarioTime.get();
    try {
      scenarioTime.set(instant);
      return work.get();
    } finally {
      if (previous == null) scenarioTime.remove();
      else scenarioTime.set(previous);
    }
  }
}
