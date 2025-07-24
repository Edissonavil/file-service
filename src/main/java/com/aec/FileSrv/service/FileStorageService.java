package com.aec.FileSrv.service; // Asegúrate de que el paquete sea correcto

import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.Repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j 
public class FileStorageService {

    private final StoredFileRepository repo;
    private final GoogleDriveService googleDriveService;
    public record UploadFileResponse(String googleDriveFileId, String originalFilename) {}

    public Resource loadAsResource(String googleDriveFileId) throws IOException { // Eliminado String uploaderId
        log.info("🔍 Solicitando archivo de Google Drive: ID={}", googleDriveFileId);
        // Primero, obtener el StoredFile para validar el uploader y obtener el uploaderId
        Optional<StoredFile> storedFileOpt = repo.findByGoogleDriveFileId(googleDriveFileId);
        if (storedFileOpt.isEmpty()) {
            throw new NoSuchFileException("Archivo no encontrado en la base de datos: " + googleDriveFileId);
        }
        StoredFile storedFile = storedFileOpt.get();

        try {
            InputStream inputStream = googleDriveService.downloadFile(storedFile.getUploader(), googleDriveFileId);
            return new InputStreamResource(inputStream);
        } catch (IOException e) {
            log.error("❌ Error al descargar archivo de Google Drive: ID={}, Error={}", googleDriveFileId, e.getMessage());
            throw new NoSuchFileException("Archivo no encontrado en Google Drive o error de acceso: " + googleDriveFileId);
        }
    }

    public String getFileContentType(String googleDriveFileId) {
        return repo.findByGoogleDriveFileId(googleDriveFileId)
                .map(StoredFile::getFileType)
                .orElse("application/octet-stream"); // Tipo MIME por defecto si no se encuentra
    }
    public UploadFileResponse storeProductFile(MultipartFile file, String uploader, Long productId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        InputStream fileInputStream = file.getInputStream();

        // Subir a Google Drive
        String googleDriveFileId = googleDriveService.uploadFile(uploader, fileInputStream, mimeType, originalFilename, null);

        StoredFile sf = new StoredFile();
        sf.setGoogleDriveFileId(googleDriveFileId);
        sf.setOriginalName(originalFilename);
        sf.setFileType(mimeType);
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setProductId(productId);
        sf.setUploadedAt(Instant.now());
        sf = repo.save(sf);
        log.info("Archivo de producto guardado en DB con Google Drive ID: {}", googleDriveFileId);

        return new UploadFileResponse(googleDriveFileId, originalFilename); // ✅ Devolver el nuevo record
    }

    // ✅ Modificado para devolver UploadFileResponse
    public UploadFileResponse storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        InputStream fileInputStream = file.getInputStream();

        // Subir a Google Drive
        String googleDriveFileId = googleDriveService.uploadFile(uploader, fileInputStream, mimeType, originalFilename, null);

        StoredFile sf = new StoredFile();
        sf.setGoogleDriveFileId(googleDriveFileId);
        sf.setOriginalName(originalFilename);
        sf.setFileType(mimeType);
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setOrderId(orderId);
        sf.setUploadedAt(Instant.now());
        sf = repo.save(sf);
        log.info("Archivo de comprobante guardado en DB con Google Drive ID: {}", googleDriveFileId);

        return new UploadFileResponse(googleDriveFileId, originalFilename); // ✅ Devolver el nuevo record
    }

    // Opcional: Método para eliminar archivos de Google Drive y de la DB
    // ✅ Ajustado el parámetro para que coincida con el uso en ProductService
    public void deleteFile(String googleDriveFileId) throws IOException { // Eliminado uploaderId
        Optional<StoredFile> storedFileOpt = repo.findByGoogleDriveFileId(googleDriveFileId);
        if (storedFileOpt.isEmpty()) {
            log.warn("Intento de eliminar archivo no encontrado en DB: {}", googleDriveFileId);
            return; // No hay nada que borrar si no está en la DB
        }
        StoredFile storedFile = storedFileOpt.get();

        googleDriveService.deleteFile(storedFile.getUploader(), googleDriveFileId); // Usar el uploader de la DB
        repo.delete(storedFile); // Eliminar de la DB
        log.info("Archivo {} eliminado de Google Drive y DB.", googleDriveFileId);
    }
}