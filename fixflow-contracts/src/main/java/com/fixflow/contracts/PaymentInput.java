package com.fixflow.contracts;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record PaymentInput(
    @NotNull
        @DecimalMin(value = "0.01", message = "Сумма должна быть положительной")
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,
    @NotBlank @Pattern(regexp = "CASH|CARD|BANK_TRANSFER|OTHER", message = "Выберите способ оплаты")
        String method,
    @Size(max = 2000) String comment) {}
