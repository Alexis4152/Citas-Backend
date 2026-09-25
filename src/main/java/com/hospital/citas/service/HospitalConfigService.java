package com.hospital.citas.service;

import com.hospital.citas.dto.request.HospitalConfigRequest;
import com.hospital.citas.dto.response.HospitalConfigResponse;
import com.hospital.citas.entity.HospitalConfig;
import org.springframework.web.multipart.MultipartFile;

public interface HospitalConfigService {
    HospitalConfigResponse get();
    HospitalConfigResponse update(HospitalConfigRequest request);
    HospitalConfigResponse updateLogo(MultipartFile logo);
    HospitalConfig getEntity();
}
