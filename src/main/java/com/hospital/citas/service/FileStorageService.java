package com.hospital.citas.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

public interface FileStorageService {

    /**
     * Valida tamaño/extensión ANTES de escribir (patrón 08: el límite de negocio por
     * servicio debe ser menor al límite global de multipart, para que este mensaje gane
     * sobre un 413 crudo del framework) y guarda el archivo en
     * {@code app.uploads.dir/<subfolder>/<uuid>.<ext>}.
     *
     * @return la ruta relativa servible bajo {@code /uploads/**} (ej. {@code doctors/uuid.jpg}).
     */
    String store(MultipartFile file, String subfolder, long maxBytes, Set<String> allowedExtensions);
}
