package com.hospital.citas.controller;

import com.hospital.citas.dto.ApiResponse;
import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.response.PatientResponse;
import com.hospital.citas.service.PatientService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Autoservicio del paciente autenticado sobre su propio registro -- distinto de
 * {@code AdminDoctorController}/{@code ReceptionController}, que operan sobre CUALQUIER
 * paciente por id. Aquí no se recibe ni se confía en ningún id: siempre se resuelve el
 * Patient a partir del usuario en sesión (ver PatientServiceImpl.findOwnPatient). */
@RestController
@RequestMapping("/api/patients/me")
@RequiredArgsConstructor
public class PatientController {

    private final PatientService patientService;

    @GetMapping
    public ApiResponse<PatientResponse> getOwn() {
        return ApiResponse.ok(patientService.getOwn());
    }

    @PutMapping("/medical-info")
    public ApiResponse<PatientResponse> updateOwnMedicalInfo(@Valid @RequestBody MedicalInfoRequest request) {
        return ApiResponse.ok(patientService.updateOwnMedicalInfo(request), "Información médica actualizada");
    }
}
