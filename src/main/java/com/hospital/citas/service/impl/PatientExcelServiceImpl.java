package com.hospital.citas.service.impl;

import com.hospital.citas.dto.request.MedicalInfoRequest;
import com.hospital.citas.dto.request.PatientRequest;
import com.hospital.citas.dto.response.PatientImportSummary;
import com.hospital.citas.entity.Patient;
import com.hospital.citas.entity.User;
import com.hospital.citas.enums.BloodType;
import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.repository.PatientRepository;
import com.hospital.citas.security.DoctorScope;
import com.hospital.citas.security.SecurityUtils;
import com.hospital.citas.service.PatientExcelService;
import com.hospital.citas.service.PatientService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exporta/importa pacientes en Excel (patrón 10). La importación reusa {@link PatientService
 * #create} -- el MISMO validador del alta individual desde la pantalla de recepción -- fila
 * por fila dentro de su propio try/catch, para que una fila mala no tumbe el resto del lote.
 */
@Service
@RequiredArgsConstructor
public class PatientExcelServiceImpl implements PatientExcelService {

    private static final String[] HEADERS = {
        "Nombre", "Apellido", "Teléfono", "Correo (opcional)",
        "Alergias (o 'Ninguna')", "Tipo de sangre (A+, A-, B+, B-, AB+, AB-, O+, O-, Otro)",
        "Tipo de sangre - detalle (solo si 'Otro')",
    };

    private static final Map<String, BloodType> BLOOD_TYPE_BY_LABEL = new LinkedHashMap<>();
    private static final Map<BloodType, String> BLOOD_TYPE_LABELS = new LinkedHashMap<>();
    static {
        addBloodType("A+", BloodType.A_POSITIVE);
        addBloodType("A-", BloodType.A_NEGATIVE);
        addBloodType("B+", BloodType.B_POSITIVE);
        addBloodType("B-", BloodType.B_NEGATIVE);
        addBloodType("AB+", BloodType.AB_POSITIVE);
        addBloodType("AB-", BloodType.AB_NEGATIVE);
        addBloodType("O+", BloodType.O_POSITIVE);
        addBloodType("O-", BloodType.O_NEGATIVE);
        addBloodType("OTRO", BloodType.OTHER);
    }

    private static void addBloodType(String label, BloodType type) {
        BLOOD_TYPE_BY_LABEL.put(label, type);
        BLOOD_TYPE_LABELS.put(type, label);
    }

    private final PatientRepository patientRepository;
    private final PatientService patientService;
    private final Validator validator;
    private final DoctorScope doctorScope;

    @Override
    public byte[] buildTemplate() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Pacientes");
            writeHeader(sheet);
            return toBytes(workbook);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar la plantilla de Excel", e);
        }
    }

    @Override
    public byte[] exportAll() {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Pacientes");
            writeHeader(sheet);
            // Un doctor solo exporta a sus pacientes; recepción/admin, a todos.
            User currentUser = SecurityUtils.getCurrentUserOrNull();
            List<Patient> patients = doctorScope.doctorIdOf(currentUser)
                    .map(doctorId -> patientRepository.findActiveOfDoctor(doctorId, currentUser.getId()))
                    .orElseGet(patientRepository::findByIsActiveTrue);
            int rowIndex = 1;
            for (Patient patient : patients) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(patient.getFirstName());
                row.createCell(1).setCellValue(patient.getLastName());
                row.createCell(2).setCellValue(patient.getPhone());
                row.createCell(3).setCellValue(patient.getEmail() != null ? patient.getEmail() : "");
                row.createCell(4).setCellValue(patient.getAllergies() != null ? patient.getAllergies() : "");
                row.createCell(5).setCellValue(patient.getBloodType() != null ? BLOOD_TYPE_LABELS.getOrDefault(patient.getBloodType(), "") : "");
                row.createCell(6).setCellValue(patient.getBloodTypeOther() != null ? patient.getBloodTypeOther() : "");
            }
            return toBytes(workbook);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo exportar el Excel de pacientes", e);
        }
    }

    @Override
    public PatientImportSummary importFromExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("El archivo está vacío");
        }
        List<PatientImportSummary.RowError> errors = new ArrayList<>();
        int created = 0;

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, formatter)) {
                    continue;
                }
                int excelRow = i + 1;
                try {
                    PatientRequest request = new PatientRequest();
                    request.setFirstName(formatter.formatCellValue(row.getCell(0)).trim());
                    request.setLastName(formatter.formatCellValue(row.getCell(1)).trim());
                    request.setPhone(formatter.formatCellValue(row.getCell(2)).trim());
                    String email = formatter.formatCellValue(row.getCell(3)).trim();
                    request.setEmail(email.isEmpty() ? null : email);

                    String allergies = formatter.formatCellValue(row.getCell(4)).trim();
                    String bloodTypeLabel = formatter.formatCellValue(row.getCell(5)).trim();
                    String bloodTypeOther = formatter.formatCellValue(row.getCell(6)).trim();

                    MedicalInfoRequest medicalInfo = new MedicalInfoRequest();
                    medicalInfo.setAllergies(allergies);
                    if (!bloodTypeLabel.isEmpty()) {
                        BloodType bloodType = BLOOD_TYPE_BY_LABEL.get(bloodTypeLabel.toUpperCase());
                        if (bloodType == null) {
                            errors.add(PatientImportSummary.RowError.builder().row(excelRow)
                                .message("Tipo de sangre \"" + bloodTypeLabel + "\" no reconocido. Usa A+, A-, B+, B-, AB+, AB-, O+, O- u Otro")
                                .build());
                            continue;
                        }
                        medicalInfo.setBloodType(bloodType);
                        medicalInfo.setBloodTypeOther(bloodType == BloodType.OTHER ? bloodTypeOther : null);
                    }
                    request.setMedicalInfo(medicalInfo);

                    Set<ConstraintViolation<PatientRequest>> violations = validator.validate(request);
                    if (!violations.isEmpty()) {
                        String message = violations.iterator().next().getMessage();
                        errors.add(PatientImportSummary.RowError.builder().row(excelRow).message(message).build());
                        continue;
                    }

                    patientService.create(request);
                    created++;
                } catch (Exception e) {
                    errors.add(PatientImportSummary.RowError.builder().row(excelRow).message(e.getMessage()).build());
                }
            }
        } catch (IOException e) {
            throw new BusinessException("No se pudo leer el archivo de Excel");
        }

        return PatientImportSummary.builder().created(created).errors(errors).build();
    }

    private void writeHeader(Sheet sheet) {
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            header.createCell(i).setCellValue(HEADERS[i]);
        }
    }

    private boolean isBlankRow(Row row, DataFormatter formatter) {
        for (int c = 0; c < HEADERS.length; c++) {
            if (!formatter.formatCellValue(row.getCell(c)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private byte[] toBytes(Workbook workbook) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        return out.toByteArray();
    }
}
