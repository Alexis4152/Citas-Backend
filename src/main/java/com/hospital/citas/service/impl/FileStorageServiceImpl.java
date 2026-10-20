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

        // La extensión la pone quien sube el archivo: se verifica que el contenido de verdad sea
        // una imagen de ese tipo (firma de bytes), para que nadie suba otra cosa renombrada.
        if (!contentMatchesExtension(file, extension)) {
            throw new BusinessException("El archivo no es una imagen válida (" + String.join(", ", allowedExtensions) + ")");
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

    private boolean contentMatchesExtension(MultipartFile file, String extension) {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            return false;
        }
        return switch (extension) {
            case "jpg", "jpeg" -> read >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "png" -> read >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "webp" -> read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null || !originalFilename.contains(".")) {
            return "";
        }
        return originalFilename.substring(originalFilename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
