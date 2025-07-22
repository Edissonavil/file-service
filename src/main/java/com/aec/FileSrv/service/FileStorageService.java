package com.aec.FileSrv.service; // Asegúrate de que el paquete sea correcto

import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.Repository.StoredFileRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant; 
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final StoredFileRepository repo;

    private Path uploadDirPath;

    @Value("${file.upload-dir}")
    private String uploadDirString;

    // Aquí había una anotación @Value sin un campo, la he omitido o la deberías revisar.
    // @Value("${file.upload-dir}")

    @PostConstruct
    public void init() throws IOException {
        this.uploadDirPath = Paths.get(uploadDirString).toAbsolutePath().normalize();
        log.info("Ruta de carga configurada: {}", uploadDirPath.toString());

        try {
            Files.createDirectories(uploadDirPath);

            Path productosDir = uploadDirPath.resolve("productos");
            Path comprobantesDir = uploadDirPath.resolve("comprobantes");

            Files.createDirectories(productosDir);
            Files.createDirectories(comprobantesDir);

            log.info("Subcarpetas 'productos' y 'comprobantes' creadas/verificadas.");
            if (!Files.isWritable(uploadDirPath)) {
                throw new IllegalStateException("No puedo escribir en el directorio base: " + uploadDirPath);
            }
        } catch (IOException e) {
            log.error("Error al inicializar directorios de carga: {}", e.getMessage(), e);
            throw e;
        }
    }

    public Resource loadAsResource(String filename, Long entityId) throws IOException {
    // Buscar primero en productos
    Path productPath = uploadDirPath.resolve("productos")
                                   .resolve(entityId.toString())
                                   .resolve(filename);
    
    if (Files.exists(productPath)) {
        return new UrlResource(productPath.toUri());
    }

    // Si no está en productos, buscar en comprobantes
    Path receiptPath = uploadDirPath.resolve("comprobantes")
                                   .resolve(entityId.toString())
                                   .resolve(filename);
    
    if (Files.exists(receiptPath)) {
        return new UrlResource(receiptPath.toUri());
    }

    throw new NoSuchFileException("Archivo no encontrado en ninguna ubicación");
}


    public String getFileContentType(String filename, Long entityId) throws IOException {
        Path pathProducto = uploadDirPath.resolve("productos").resolve(String.valueOf(entityId)).resolve(filename);
        Path pathComprobante = uploadDirPath.resolve("comprobantes").resolve(String.valueOf(entityId)).resolve(filename);

        if (Files.exists(pathProducto)) return Files.probeContentType(pathProducto);
        if (Files.exists(pathComprobante)) return Files.probeContentType(pathComprobante);

        return "application/octet-stream";
    }

    public StoredFile storeProductFile(MultipartFile file, String uploader, Long productId) throws IOException {
        Path targetDir = Paths.get(uploadDirString, "productos", productId.toString());
        Files.createDirectories(targetDir);
        String filename = UUID.randomUUID() + "-" + file.getOriginalFilename();
        Path dest = targetDir.resolve(filename);
        file.transferTo(dest);

        StoredFile sf = new StoredFile();
        sf.setFilename(filename);
        sf.setOriginalName(file.getOriginalFilename());
        sf.setFileType(file.getContentType());
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setProductId(productId);
        sf.setUploadedAt(Instant.now()); // ¡Cambio aquí: LocalDateTime.now() -> Instant.now()!
        sf = repo.save(sf);
        return sf;
    }

    public StoredFile storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        Path targetDir = Paths.get(uploadDirString, "comprobantes", orderId.toString());
        Files.createDirectories(targetDir);
        String filename = UUID.randomUUID() + "-" + file.getOriginalFilename();
        Path dest = targetDir.resolve(filename);
        file.transferTo(dest);

        StoredFile sf = new StoredFile();
        sf.setFilename(filename);
        sf.setOriginalName(file.getOriginalFilename());
        sf.setFileType(file.getContentType());
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setOrderId(orderId);
        sf.setUploadedAt(Instant.now()); // ¡Cambio aquí: LocalDateTime.now() -> Instant.now()!
        sf = repo.save(sf);
        return sf;
    }
}