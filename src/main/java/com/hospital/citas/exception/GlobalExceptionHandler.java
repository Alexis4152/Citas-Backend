package com.hospital.citas.exception;

import com.hospital.citas.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        // Errores de campo y de reglas entre campos (@AssertTrue de clase / validadores de clase).
        String msg = ex.getBindingResult().getAllErrors().stream()
                .map(org.springframework.validation.ObjectError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(ApiResponse.error(msg));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicate(DuplicateResourceException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(SlotUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleSlotUnavailable(SlotUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    /** 409 con {@code code=SLOT_HELD}: el frontend lo distingue de "ya reservado" para mostrar
     * "alguien lo está apartando" y la hora en que se libera si no lo confirma. */
    @ExceptionHandler(SlotHeldException.class)
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> handleSlotHeld(SlotHeldException ex) {
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("code", "SLOT_HELD");
        data.put("heldUntil", ex.getHeldUntil());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiResponse<>(false, ex.getMessage(), data));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        // Segunda linea de defensa: el indice unico parcial de appointments salto porque dos
        // requests concurrentes pasaron el chequeo pesimista casi al mismo tiempo.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("El horario seleccionado ya no está disponible"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // JSON mal formado o con un valor de tipo inválido (ej. una fecha mal escrita): es un
        // error del cliente (400), no del servidor.
        return ResponseEntity.badRequest().body(ApiResponse.error("El cuerpo de la solicitud no es válido"));
    }

    /** Parámetro o archivo faltante, o con un tipo inválido (ej. ?status=OTRO): error del cliente. */
    @ExceptionHandler({org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadParameter(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("La solicitud está incompleta o tiene un dato inválido"));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        // Bloqueo optimista de la cita (@Version): otra persona (o el sistema) modificó la
        // misma cita justo antes que esta acción.
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("La cita fue modificada por otra persona mientras la editabas. Recarga e intenta de nuevo."));
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiResponse<Void>> handlePaymentGateway(PaymentGatewayException ex) {
        log.error("Falla de la pasarela de pagos", ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCreds(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Credenciales inválidas"));
    }

    /** Cuenta desactivada: Spring lo revisa ANTES que la contraseña, así que se responde igual que
     * unas credenciales inválidas para no confirmar a un tercero que esa cuenta existe (antes
     * terminaba en un 500). */
    @ExceptionHandler(org.springframework.security.authentication.DisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(org.springframework.security.authentication.DisabledException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error("Credenciales inválidas"));
    }

    @ExceptionHandler(com.hospital.citas.security.PermissionService.PermissionDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handlePermissionDenied(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Acceso denegado"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    // Catch-all: no se expone el mensaje real de la excepción al cliente para no filtrar
    // detalles internos (stack traces, nombres de clases, etc.); solo se registra en el log.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Error no controlado", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("Ocurrió un error inesperado. Intenta de nuevo más tarde."));
    }
}
