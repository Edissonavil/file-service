package com.aec.FileSrv.service; // Asegúrate de que el paquete sea correcto

import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.Repository.UserGoogleDriveTokenRepository;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.UUID; // Para generar nombres únicos si es necesario, aunque Google Drive ya da un ID

@Service
@RequiredArgsConstructor
public class FileStorageService {
    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final StoredFileRepository repo;
    private final GoogleDriveService googleDriveService; // Inyectar GoogleDriveService

    // Ya no se necesita @Value("${file.upload-dir}") ni init() para directorios locales
    // Puedes eliminar el campo uploadDirPath y el método init() si ya no usas almacenamiento local.
    // Para esta integración, asumimos que todo va a Google Drive.

    // El método loadAsResource ahora descarga desde Google Drive
    public Resource loadAsResource(String googleDriveFileId, String uploaderId) throws IOException {
        log.info("🔍 Solicitando archivo de Google Drive: ID={}, Uploader={}", googleDriveFileId, uploaderId);
        try {
            InputStream inputStream = googleDriveService.downloadFile(uploaderId, googleDriveFileId);
            return new InputStreamResource(inputStream);
        } catch (IOException e) {
            log.error("❌ Error al descargar archivo de Google Drive: ID={}, Error={}", googleDriveFileId, e.getMessage());
            // Puedes buscar en la base de datos para ver si el archivo existe
            // y lanzar una excepción más específica si no se encuentra.
            throw new NoSuchFileException("Archivo no encontrado en Google Drive o error de acceso: " + googleDriveFileId);
        }
    }

    // El método getFileContentType ahora obtiene el tipo MIME de la base de datos
    public String getFileContentType(String googleDriveFileId) {
        return repo.findByGoogleDriveFileId(googleDriveFileId)
                .map(StoredFile::getFileType)
                .orElse("application/octet-stream"); // Tipo MIME por defecto si no se encuentra
    }

    public StoredFile storeProductFile(MultipartFile file, String uploader, Long productId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        InputStream fileInputStream = file.getInputStream();

        // Subir a Google Drive
        String googleDriveFileId = googleDriveService.uploadFile(uploader, fileInputStream, mimeType, originalFilename, null); // null para subir a la raíz o a la carpeta por defecto

        StoredFile sf = new StoredFile();
        sf.setGoogleDriveFileId(googleDriveFileId); // Guardamos el ID de Google Drive
        sf.setOriginalName(originalFilename);
        sf.setFileType(mimeType);
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setProductId(productId);
        sf.setUploadedAt(Instant.now());
        sf = repo.save(sf);
        log.info("Archivo de producto guardado en DB con Google Drive ID: {}", googleDriveFileId);
        return sf;
    }

    public StoredFile storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        InputStream fileInputStream = file.getInputStream();

        // Subir a Google Drive
        String googleDriveFileId = googleDriveService.uploadFile(uploader, fileInputStream, mimeType, originalFilename, null); // null para subir a la raíz o a la carpeta por defecto

        StoredFile sf = new StoredFile();
        sf.setGoogleDriveFileId(googleDriveFileId); // Guardamos el ID de Google Drive
        sf.setOriginalName(originalFilename);
        sf.setFileType(mimeType);
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setOrderId(orderId);
        sf.setUploadedAt(Instant.now());
        sf = repo.save(sf);
        log.info("Archivo de comprobante guardado en DB con Google Drive ID: {}", googleDriveFileId);
        return sf;
    }

    // Opcional: Método para eliminar archivos de Google Drive y de la DB
    public void deleteFile(String googleDriveFileId, String uploaderId) throws IOException {
        googleDriveService.deleteFile(uploaderId, googleDriveFileId);
        repo.findByGoogleDriveFileId(googleDriveFileId).ifPresent(repo::delete);
        log.info("Archivo {} eliminado de Google Drive y DB.", googleDriveFileId);
    }
}