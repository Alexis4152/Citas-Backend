package com.hospital.citas.service;

import com.hospital.citas.dto.PageResponse;
import com.hospital.citas.dto.request.PrescriptionRequest;
import com.hospital.citas.dto.response.PrescriptionResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PrescriptionService {

    /** El doctor autenticado genera una receta para una de sus propias citas. */
    PrescriptionResponse create(PrescriptionRequest request);

    /** El doctor anula una receta suya (con motivo): no se borra ni se edita -- queda en el
     * historial marcada como anulada y su PDF lo indica. Para corregirla, se anula y se emite otra. */
    PrescriptionResponse voidPrescription(Long prescriptionId, String reason);

    /** El doctor decide (se le pregunta cada vez, nunca automático) enviarle esta receta ya
     * generada al correo del paciente. Lanza si el paciente no tiene correo capturado, para
     * poder avisarle al doctor en vez de fallar en silencio. */
    void sendByEmail(Long prescriptionId);

    /** PDF de una receta -- el doctor solo puede descargar las de sus propias citas. */
    byte[] generatePdfForDoctor(Long prescriptionId);

    /** PDF de una receta -- recepción/admin, respetando la restricción de especialidad de la
     * recepcionista (ver AppointmentServiceImpl.checkReceptionistSpecialtyAllowed). */
    byte[] generatePdfForStaff(Long prescriptionId);

    /** Recetas ya generadas para una cita puntual -- el doctor las ve desde el detalle de esa
     * cita en su agenda (para no perder de vista lo que ya recetó ahí). */
    List<PrescriptionResponse> listForAppointmentAsDoctor(Long appointmentId);

    /** Historial paginado y buscable por paciente de TODAS las recetas que el doctor
     * autenticado ha emitido -- el módulo "Recetas" del doctor. */
    PageResponse<PrescriptionResponse> searchForOwnDoctor(String patientQuery, Pageable pageable);

    /** Historial de recetas de un paciente para recepción/admin (ficha del paciente) --
     * filtrado por especialidad si la recepcionista tiene una asignada. */
    List<PrescriptionResponse> listForPatientAsStaff(Long patientId);
}
