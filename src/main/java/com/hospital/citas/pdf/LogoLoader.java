package com.hospital.citas.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logo del hospital para los PDFs. La imagen decodificada se guarda en caché por ruta (cada
 * hospital tiene la suya; subir un logo nuevo cambia la ruta), así no se relee de disco en cada
 * comprobante. Si falta o no se puede leer, el PDF sale igual con las iniciales del hospital.
 */
@Component
public class LogoLoader {

    private static final Logger log = LoggerFactory.getLogger(LogoLoader.class);

    @Value("${app.uploads.dir}")
    private String uploadsDir;

    private final Map<String, BufferedImage> cache = new ConcurrentHashMap<>();

    public PDImageXObject load(PDDocument document, String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) {
            return null;
        }
        try {
            BufferedImage image = cache.get(logoUrl);
            if (image == null) {
                Path path = Paths.get(uploadsDir, logoUrl);
                if (!Files.exists(path)) {
                    return null;
                }
                image = ImageIO.read(path.toFile());
                if (image == null) {
                    return null;
                }
                if (cache.size() > 200) {
                    cache.clear();
                }
                cache.put(logoUrl, image);
            }
            return LosslessFactory.createFromImage(document, image);
        } catch (Exception e) {
            log.warn("No se pudo cargar el logo del hospital para el PDF; se usan sus iniciales", e);
            return null;
        }
    }
}
