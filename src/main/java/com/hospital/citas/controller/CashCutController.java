package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.CashCutCloseRequest;
import com.hospital.citas.dto.request.CashCutOpenRequest;
import com.hospital.citas.dto.response.CashCutResponse;
import com.hospital.citas.service.CashCutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/** Corte de caja de recepción (y de admin). Bajo /api/reception: RECEPTIONIST y ADMIN. */
@RestController
@RequestMapping("/api/reception/cash-cut")
@RequiredArgsConstructor
public class CashCutController {

    private final CashCutService cashCutService;

    /** Mi corte abierto con totales en vivo; {@code data} nulo si no tengo uno abierto. */
    @GetMapping("/current")
    public ApiResponse<CashCutResponse> current() {
        return ApiResponse.ok(cashCutService.current());
    }

    @PostMapping("/open")
    public ApiResponse<CashCutResponse> open(@Valid @RequestBody CashCutOpenRequest request) {
        return ApiResponse.ok(cashCutService.open(request), "Corte de caja abierto");
    }

    @PostMapping("/{id}/close")
    public ApiResponse<CashCutResponse> close(@PathVariable Long id, @Valid @RequestBody CashCutCloseRequest request) {
        return ApiResponse.ok(cashCutService.close(id, request), "Corte de caja cerrado");
    }

    @GetMapping("/{id}")
    public ApiResponse<CashCutResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(cashCutService.get(id));
    }

    /** Historial: los míos (recepción) o los de todos (admin). */
    @GetMapping
    public ApiResponse<PageResponse<CashCutResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(cashCutService.list(PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt"))));
    }
}
