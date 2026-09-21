package com.cafeorbe.auction.api;

import com.cafeorbe.auction.domain.ReglaDeNegocioException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ManejadorDeErrores {

    public record ApiError(int status, String mensaje, Map<String, String> campos) {
    }

    private static ResponseEntity<ApiError> error(HttpStatus estado, String mensaje, Map<String, String> campos) {
        return ResponseEntity.status(estado).body(new ApiError(estado.value(), mensaje, campos));
    }

    @ExceptionHandler(ReglaDeNegocioException.class)
    public ResponseEntity<ApiError> reglaDeNegocio(ReglaDeNegocioException e) {
        HttpStatus estado = switch (e.getTipo()) {
            case VALIDACION -> HttpStatus.BAD_REQUEST;
            case PROHIBIDO -> HttpStatus.FORBIDDEN;
            case NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
            case CONFLICTO -> HttpStatus.CONFLICT;
        };
        return error(estado, e.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        String mensaje = campos.values().stream().findFirst().orElse("Datos inválidos");
        return error(HttpStatus.BAD_REQUEST, mensaje, campos);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> solicitudInvalida(Exception e) {
        return error(HttpStatus.BAD_REQUEST, "La solicitud no es válida", Map.of());
    }

    @ExceptionHandler(SesionRequeridaException.class)
    public ResponseEntity<ApiError> sinSesion(SesionRequeridaException e) {
        return error(HttpStatus.UNAUTHORIZED, e.getMessage(), Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> concurrencia(ObjectOptimisticLockingFailureException e) {
        return error(HttpStatus.CONFLICT, "La subasta cambió mientras la editabas, intenta de nuevo", Map.of());
    }
}
