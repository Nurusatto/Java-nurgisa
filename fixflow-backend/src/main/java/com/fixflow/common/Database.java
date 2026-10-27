package com.fixflow.common;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Database {
  private final JdbcTemplate jdbc;

  public Database(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> rows(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, index) -> {
          var row = new LinkedHashMap<String, Object>();
          var metadata = rs.getMetaData();
          for (int i = 1; i <= metadata.getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof Timestamp t) value = t.toInstant();
            row.put(metadata.getColumnLabel(i), value);
          }
          return row;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var found = rows(sql, args);
    if (found.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Запись не найдена");
    return found.getFirst();
  }

  public Map<String, Object> get(String table, UUID id) {
    return one("select * from " + table + " where id=?", id);
  }

  public Map<String, Object> lock(String table, UUID id) {
    return one("select * from " + table + " where id=? for update", id);
  }

  public int execute(String sql, Object... args) {
    return jdbc.update(sql, Arrays.stream(args).map(Database::sqlValue).toArray());
  }

  public UUID insert(String table, Map<String, ?> values) {
    var data = new LinkedHashMap<String, Object>(values);
    UUID id = data.containsKey("id") ? Input.id(data.get("id")) : UUID.randomUUID();
    data.put("id", id);
    execute(
        "insert into "
            + table
            + " ("
            + String.join(",", data.keySet())
            + ") values ("
            + String.join(",", Collections.nCopies(data.size(), "?"))
            + ")",
        data.values().toArray());
    return id;
  }

  public void change(String table, UUID id, Map<String, ?> fields) {
    var args = new ArrayList<Object>(fields.values());
    args.add(id);
    execute(
        "update "
            + table
            + " set "
            + String.join(",", fields.keySet().stream().map(k -> k + "=?").toList())
            + " where id=?",
        args.toArray());
  }

  public static LinkedHashMap<String, Object> fields(Object... pairs) {
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < pairs.length; i += 2) result.put(pairs[i].toString(), pairs[i + 1]);
    return result;
  }

  private static Object sqlValue(Object value) {
    return value instanceof Instant t ? Timestamp.from(t) : value;
  }
}
