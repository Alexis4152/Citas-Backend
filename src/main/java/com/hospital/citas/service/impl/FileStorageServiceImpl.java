package com.hospital.citas.service.impl;

import com.hospital.citas.exception.BusinessException;
import com.hospital.citas.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageServiceImpl implements FileStorageService {

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    @Override
    public String store(MultipartFile file, String subfolder, long maxBytes, Set<String> allowedExtensions) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("El archivo está vacío");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException("El archivo supera el tamaño máximo permitido (" + (maxBytes / (1024 * 1024)) + "MB)");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (extension.isEmpty() || !allowedExtensions.contains(extension)) {
            throw new BusinessException("Formato de archivo no permitido (" + String.join(", ", allowedExtensions) + ")");
        }

        try {
            Path targetDir = Paths.get(uploadsDir, subfolder);
            Files.createDirectories(targetDir);
            String fileName = UUID.randomUUID() + "." + extension;
            Path targetFile = targetDir.resolve(fileName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return subfolder + "/" + fileName;
        } catch (IOException e) {
            throw new BusinessException("No se pudo guardar el archivo");
        }
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
