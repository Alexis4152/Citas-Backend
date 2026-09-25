package com.hospital.citas.service;

import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.request.PatientRequest;
import com.hospital.citas.dto.response.PatientResponse;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.User;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PatientService {

    Page<PatientResponse> search(String query, Long doctorId, Long specialtyId, Pageable pageable);

    PatientResponse getById(Long id);

    PatientResponse create(PatientRequest request);

    /** Usada por recepción/agendado: crea el Patient (sin User) a partir de los datos capturados. */
    Patient findOrCreateGuestPatient(String firstName, String lastName, String phone, String email);

    /** Usada en el registro: crea (o reutiliza) el Patient ligado a la cuenta recién creada. */
    Patient findOrCreatePatientForUser(User user);

    Patient getEntityById(Long id);

    /** Aplica alergias/tipo de sangre sobre un Patient YA PERSISTIDO y guarda -- usada por el
     * agendado (bookGuest/bookSelf en AppointmentServiceImpl, sobre el Patient recién
     * encontrado/creado) y por las ediciones de abajo. Deja el rastro de auditoría dedicado
     * (Patient#medicalInfoUpdatedBy/At); `modifiedBy` nulo = lo reportó un invitado sin cuenta
     * identificada. */
    void applyMedicalInfo(Patient patient, MedicalInfoRequest request, User modifiedBy);

    /** Recepción/doctor/admin editando la info médica de cualquier paciente por id. */
    PatientResponse updateMedicalInfo(Long patientId, MedicalInfoRequest request);

    /** El propio paciente autenticado editando la suya. */
    PatientResponse updateOwnMedicalInfo(MedicalInfoRequest request);

    /** Otros pacientes activos con el mismo teléfono o correo (posibles duplicados). */
    List<PatientResponse> findDuplicates(Long patientId);

    /** Fusiona {@code sourceId} en {@code targetId}: las citas (y con ellas las recetas) del
     * origen pasan al destino y el origen queda inactivo -- nada se borra. Si solo el origen
     * tiene cuenta de acceso, la cuenta pasa al destino; si ambos tienen, se rechaza. */
    PatientResponse merge(Long sourceId, Long targetId);

    /** Info del paciente ligado a la cuenta autenticada (para prellenar "Mi información médica"). */
    PatientResponse getOwn();
}
