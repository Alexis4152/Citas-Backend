package com.hospital.citas.config;

import com.hospital.citas.security.AuditInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Interceptores que corren en todos los perfiles (la bitácora también se prueba en tests). */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuditInterceptor auditInterceptor;
    private final com.hospital.citas.security.PermissionInterceptor permissionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(auditInterceptor).addPathPatterns("/api/**");
    }
}
