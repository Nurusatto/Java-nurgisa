package com.fixflow.common;

import static com.fixflow.common.Database.fields;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fixflow.security.Actors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Idempotency {
  private final Database db;
  private final Actors actors;
  private final ObjectMapper json;
  private final DomainTime time;

  public Idempotency(Database db, Actors actors, ObjectMapper json, DomainTime time) {
    this.db = db;
    this.actors = actors;
    this.json =
        json.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    this.time = time;
  }

  @Transactional
  public Map<String, Object> run(
      String operation,
      String key,
      Map<String, Object> body,
      Supplier<Map<String, Object>> command) {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw ApiException.invalid("Требуется Idempotency-Key (8–100 букв, цифр, '-' или '_')");
    UUID actor = actors.current().id();
    String hash;
    try {
      hash = sha256(json.writeValueAsString(body));
    } catch (Exception e) {
      throw ApiException.invalid("Некорректный запрос");
    }
    db.rows(
        "select pg_advisory_xact_lock(hashtextextended(?,0))", actor + ":" + operation + ":" + key);
    var previous =
        db.rows(
            "select * from idempotency_record where actor_id=? and operation=? and request_key=?",
            actor,
            operation,
            key);
    if (!previous.isEmpty()) {
      if (!previous.getFirst().get("request_hash").equals(hash))
        throw ApiException.conflict("Этот ключ уже использован с другими данными");
      try {
        return json.readValue(
            previous.getFirst().get("response_json").toString(), new TypeReference<>() {});
      } catch (Exception e) {
        throw new IllegalStateException("Stored response is invalid", e);
      }
    }
    var result = command.get();
    try {
      db.insert(
          "idempotency_record",
          fields(
              "actor_id",
              actor,
              "operation",
              operation,
              "request_key",
              key,
              "request_hash",
              hash,
              "response_json",
              json.writeValueAsString(result),
              "created_at",
              time.now()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return result;
  }

  public static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
