package com.hospital.citas.service;

import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.CashCutCloseRequest;
import com.hospital.citas.dto.request.CashCutOpenRequest;
import com.hospital.citas.dto.response.CashCutResponse;
import org.springframework.data.domain.Pageable;

/** Corte de caja por turno de cada persona de recepción (o admin): abre con su fondo inicial,
 * cobra citas y cierra contando el efectivo. El ADMIN además ve los cortes de todos. */
public interface CashCutService {

    /** Corte abierto de quien consulta (con totales en vivo), o null si no tiene. */
    CashCutResponse current();

    CashCutResponse open(CashCutOpenRequest request);

    CashCutResponse close(Long cutId, CashCutCloseRequest request);

    /** Detalle completo: dueño del corte o ADMIN. */
    CashCutResponse get(Long cutId);

    /** Historial: cada quien ve los suyos; el ADMIN, los de todos. */
    PageResponse<CashCutResponse> list(Pageable pageable);
}
