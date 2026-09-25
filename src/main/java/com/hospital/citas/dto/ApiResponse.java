package com.hospital.citas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envoltura genérica de todas las respuestas de la API: {@code {success, message, data}}.
 * Se usa siempre a través de los métodos de fábrica estáticos para mantener el contrato
 * consistente en toda la app.
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data)             { return new ApiResponse<>(true,  null, data); }
    public static <T> ApiResponse<T> ok(T data, String msg) { return new ApiResponse<>(true,  msg,  data); }
    public static <T> ApiResponse<T> error(String msg)      { return new ApiResponse<>(false, msg,  null); }
}
