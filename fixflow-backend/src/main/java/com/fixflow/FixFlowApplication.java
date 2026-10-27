package com.fixflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class FixFlowApplication {
  public static void main(String[] args) {
    SpringApplication.run(FixFlowApplication.class, args);
  }
}
