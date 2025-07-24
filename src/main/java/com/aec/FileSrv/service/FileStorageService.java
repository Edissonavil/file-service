package com.aec.FileSrv.service;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.model.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private final StoredFileRepository repo;
    private final GoogleDriveService googleDriveService;

    public record UploadFileResponse(String googleDriveFileId, String originalFilename) {}

    // El método loadAsResource ahora descarga desde Google Drive
    // Ya no necesita el uploaderId aquí, el GoogleDriveService lo obtiene de la DB si es necesario.
 /*    public Resource loadAsResource(String googleDriveFileId) throws IOException { // <-- Eliminado String uploaderId
        log.info("🔍 Solicitando archivo de Google Drive: ID={}", googleDriveFileId);
        Optional<StoredFile> storedFileOpt = repo.findByGoogleDriveFileId(googleDriveFileId);
        if (storedFileOpt.isEmpty()) {
            throw new NoSuchFileException("Archivo no encontrado en la base de datos: " + googleDriveFileId);
        }
        // El GoogleDriveService ahora usa las credenciales del usuario API predefinido.
        InputStream inputStream = googleDriveService.downloadFile(googleDriveFileId); // <-- Eliminado uploaderId
        return new InputStreamResource(inputStream);
    }
*/
    public String getFileContentType(String googleDriveFileId) {
        return repo.findByGoogleDriveFileId(googleDriveFileId)
                .map(StoredFile::getFileType)
                .orElse("application/octet-stream");
    }

    public UploadFileResponse storeProductFile(MultipartFile file, String uploader, Long productId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        InputStream fileInputStream = file.getInputStream();

        // Subir a Google Drive usando las credenciales del usuario de la API
        String googleDriveFileId = googleDriveService.uploadFile(fileInputStream, mimeType, originalFilename, null); // <-- Eliminado uploader y folderId, usa el default

        StoredFile sf = new StoredFile();
        sf.setGoogleDriveFileId(googleDriveFileId);
        sf.setOriginalName(originalFilename);
        sf.setFileType(mimeType);
        sf.setSize(file.getSize());
        sf.setUploader(uploader); // Guardamos el uploader real (colaborador), pero no se usa para GD
        sf.setProductId(productId);
        sf.setUploadedAt(Instant.now());
        sf = repo.save(sf);
        log.info("Archivo de producto guardado en DB con Google Drive ID: {}", googleDriveFileId);
        return new UploadFileResponse(googleDriveFileId, originalFilename);
    }

    public UploadFileResponse storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String mimeType = file.getContentType();
        InputStream fileInputStream = file.getInputStream();

        // Subir a Google Drive usando las credenciales del usuario de la API
        String googleDriveFileId = googleDriveService.uploadFile(fileInputStream, mimeType, originalFilename, null); // <-- Eliminado uploader y folderId, usa el default

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
        return new UploadFileResponse(googleDriveFileId, originalFilename);
    }

    public void deleteFile(String googleDriveFileId) throws IOException { // <-- Eliminado String uploaderId
        Optional<StoredFile> storedFileOpt = repo.findByGoogleDriveFileId(googleDriveFileId);
        if (storedFileOpt.isEmpty()) {
            log.warn("Intento de eliminar archivo no encontrado en DB: {}", googleDriveFileId);
            return;
        }
        StoredFile storedFile = storedFileOpt.get();

        // El GoogleDriveService ahora usa las credenciales del usuario API predefinido.
        googleDriveService.deleteFile(googleDriveFileId); // <-- Eliminado uploaderId
        repo.delete(storedFile);
        log.info("Archivo {} eliminado de Google Drive y DB.", googleDriveFileId);
    }
}