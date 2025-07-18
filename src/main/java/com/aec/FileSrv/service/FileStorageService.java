package com.aec.FileSrv.service;

import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.Repository.StoredFileRepository;
import jakarta.annotation.PostConstruct; // Importar PostConstruct
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger; // Añadir
import org.slf4j.LoggerFactory; // Añadir

@Service
@RequiredArgsConstructor
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class); // Añadir

    private final StoredFileRepository repo;

    private Path uploadDirPath;

    @Value("${filesrv.upload-dir}")
    private String uploadDirString;

    @Value("${file.upload-dir}")

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

    // Método unificado para cargar recursos
    public Resource loadAsResource(String filename, Long entityId) throws MalformedURLException, NoSuchFileException {
        Path pathProducto = uploadDirPath.resolve("productos").resolve(String.valueOf(entityId)).resolve(filename);
        Path pathComprobante = uploadDirPath.resolve("comprobantes").resolve(String.valueOf(entityId)).resolve(filename);

        if (Files.exists(pathProducto)) return new UrlResource(pathProducto.toUri());
        if (Files.exists(pathComprobante)) return new UrlResource(pathComprobante.toUri());

        throw new NoSuchFileException("Archivo no encontrado: " + filename + " para entidad " + entityId);
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

    // —————————————————————————————
    // guarda metadata en BD sin helper
    StoredFile sf = new StoredFile();
    sf.setFilename(filename);
    sf.setOriginalName(file.getOriginalFilename());
    sf.setFileType(file.getContentType());
    sf.setSize(file.getSize());
    sf.setUploader(uploader);
    sf.setProductId(productId);
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
    sf = repo.save(sf);
    return sf;
}


   
}