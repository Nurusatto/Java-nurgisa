package com.fixflow.common;

import com.fixflow.contracts.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class Errors {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiError> business(ApiException e, HttpServletRequest request) {
    if (e instanceof RequestValidation.Fields fields)
      return ResponseEntity.status(400)
          .body(
              new ApiError(
                  Instant.now(),
                  400,
                  e.code(),
                  e.getMessage(),
                  request.getRequestURI(),
                  fields.fields()));
    return response(e.status(), e.code(), e.getMessage(), request);
  }

  @ExceptionHandler(EmptyResultDataAccessException.class)
  ResponseEntity<ApiError> missing(Exception e, HttpServletRequest request) {
    return response(404, "NOT_FOUND", "Запись не найдена", request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiError> integrity(Exception e, HttpServletRequest request) {
    return response(
        409, "DATA_CONFLICT", "Операция нарушает уникальность или связь данных", request);
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
    org.springframework.web.bind.MissingServletRequestParameterException.class,
    java.time.DateTimeException.class
  })
  ResponseEntity<ApiError> invalid(Exception e, HttpServletRequest request) {
    return response(400, "VALIDATION_ERROR", "Проверьте формат и обязательные поля", request);
  }

  @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
  ResponseEntity<ApiError> header(Exception e, HttpServletRequest request) {
    return response(400, "VALIDATION_ERROR", "Передайте обязательный заголовок операции", request);
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<ApiError> noRoute(Exception e, HttpServletRequest request) {
    return response(404, "NOT_FOUND", "Адрес не найден", request);
  }

  @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
  ResponseEntity<ApiError> method(Exception e, HttpServletRequest request) {
    return response(405, "METHOD_NOT_ALLOWED", "Метод не поддерживается", request);
  }

  @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ApiError> media(Exception e, HttpServletRequest request) {
    return response(
        415, "UNSUPPORTED_MEDIA_TYPE", "Используйте Content-Type: application/json", request);
  }

  @ExceptionHandler({
    org.springframework.dao.PessimisticLockingFailureException.class,
    org.springframework.dao.OptimisticLockingFailureException.class
  })
  ResponseEntity<ApiError> concurrency(Exception e, HttpServletRequest request) {
    return response(
        409,
        "CONCURRENT_CHANGE",
        "Данные изменяются другим сотрудником. Обновите запись и повторите операцию",
        request);
  }

  @ExceptionHandler(org.springframework.jdbc.CannotGetJdbcConnectionException.class)
  ResponseEntity<ApiError> connection(Exception e, HttpServletRequest request) {
    return response(503, "DATABASE_UNAVAILABLE", "База данных временно недоступна", request);
  }

  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  ResponseEntity<ApiError> database(Exception e, HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("Database operation failed: {}", e.getClass().getSimpleName());
    return response(500, "DATABASE_ERROR", "Не удалось обработать данные", request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> internal(Exception e, HttpServletRequest request) {
    org.slf4j.LoggerFactory.getLogger(Errors.class)
        .error("Request failed: {}", e.getClass().getSimpleName());
    return response(500, "INTERNAL_ERROR", "Не удалось выполнить операцию", request);
  }

  private ResponseEntity<ApiError> response(
      int status, String code, String message, HttpServletRequest request) {
    String path =
        request.getRequestURI().startsWith("/api/v1/public/")
            ? "/api/v1/public/repair-status/[redacted]"
            : request.getRequestURI();
    return ResponseEntity.status(status)
        .body(new ApiError(Instant.now(), status, code, message, path, Map.of()));
  }
}
