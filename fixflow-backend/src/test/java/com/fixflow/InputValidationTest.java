package com.fixflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fixflow.common.ApiException;
import com.fixflow.common.Input;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputValidationTest {
  @Test
  void moneyRejectsNegativeAmountsExcessFractionsAndExponentOverflowBeforePersistence() {
    for (String invalid : new String[] {"-0.01", "1.001", "1E+1000000", "NaN"})
      assertThrows(ApiException.class, () -> Input.money(invalid));
    assertThat(Input.money("9999999999999999.99")).isEqualByComparingTo("9999999999999999.99");
    assertThat(Input.money("2").scale()).isEqualTo(2);
  }

  @Test
  void stockQuantitiesRespectTheDatabaseRangeAndPrecision() {
    for (String invalid : new String[] {"0", "-1", "0.0001", "1E+1000000", "1000000000"})
      assertThrows(ApiException.class, () -> Input.quantity(invalid));
    assertThat(Input.quantity("999999999.999")).isEqualByComparingTo("999999999.999");
    assertThat(Input.quantity("0.001")).isEqualByComparingTo("0.001");
  }

  @Test
  void passwordLimitCountsUtf8BytesAndPreservesWhitespace() {
    String password = "  Секретная фраза 2026!  ";
    assertThat(Input.secret(Map.of("password", password), "password", 100, true))
        .isEqualTo(password);
    assertThat(Input.secret(Map.of("password", "я".repeat(36)), "password", 100, true)).hasSize(36);
    assertThrows(
        ApiException.class,
        () -> Input.secret(Map.of("password", "я".repeat(37)), "password", 100, true));
    assertThrows(
        ApiException.class,
        () -> Input.secret(Map.of("password", "a".repeat(73)), "password", 100, true));
  }
}
