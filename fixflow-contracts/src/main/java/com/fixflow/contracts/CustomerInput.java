package com.fixflow.contracts;

import jakarta.validation.constraints.*;

public record CustomerInput(
    @NotBlank(message = "Укажите ФИО") @Size(max = 150) String full_name,
    @NotBlank(message = "Укажите телефон")
        @Pattern(regexp = "[+0-9 ()-]{7,32}", message = "Некорректный формат телефона")
        String phone,
    @Email(message = "Некорректный email") @Size(max = 254) String email,
    @Size(max = 4000) String comment,
    Boolean active) {}
