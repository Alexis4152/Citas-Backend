package com.hospital.citas.service;

import com.hospital.citas.dto.request.EmailConfigRequest;
import com.hospital.citas.dto.response.EmailConfigResponse;
import com.hospital.citas.entity.EmailConfig;

public interface EmailConfigService {
    EmailConfigResponse get();
    EmailConfigResponse update(EmailConfigRequest request);
    EmailConfig getEntity();
}
