package com.aec.FileSrv.service;

import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.Repository.StoredFileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
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

    public record UploadFileResponse(String driveFileId, String originalFilename) {}

    public Resource loadAsResource(String driveFileId) throws IOException {
        log.info("🔍 Solicitando archivo de Google Drive: ID={}", driveFileId);
        Optional<StoredFile> storedFileOpt = repo.findByDriveFileId(driveFileId);
        if (storedFileOpt.isEmpty()) {
            throw new NoSuchFileException("Archivo no encontrado en la base de datos: " + driveFileId);
        }
        InputStream inputStream = googleDriveService.downloadFile(driveFileId);
        return new InputStreamResource(inputStream);
    }

    public String getFileContentType(String driveFileId) {
        return repo.findByDriveFileId(driveFileId)
                .map(StoredFile::getFileType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    public UploadFileResponse storeProductFile(MultipartFile file, String uploader, Long productId) throws IOException {
        String driveId = googleDriveService.uploadFile(file, true);
        StoredFile sf = new StoredFile();
        sf.setFilename(file.getOriginalFilename());
        sf.setOriginalName(file.getOriginalFilename());
        sf.setFileType(file.getContentType());
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setProductId(productId);
        sf.setUploadedAt(Instant.now());
        sf.setDriveFileId(driveId);
        sf = repo.save(sf);
        log.info("Archivo de producto guardado. Drive ID: {}", driveId);
        return new UploadFileResponse(driveId, file.getOriginalFilename());
    }

    public UploadFileResponse storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        String driveId = googleDriveService.uploadFile(file, false);
        StoredFile sf = new StoredFile();
        sf.setFilename(file.getOriginalFilename());
        sf.setOriginalName(file.getOriginalFilename());
        sf.setFileType(file.getContentType());
        sf.setSize(file.getSize());
        sf.setUploader(uploader);
        sf.setOrderId(orderId);
        sf.setUploadedAt(Instant.now());
        sf.setDriveFileId(driveId);
        sf = repo.save(sf);
        log.info("Archivo de comprobante guardado. Drive ID: {}", driveId);
        return new UploadFileResponse(driveId, file.getOriginalFilename());
    }

    public void deleteFile(String driveFileId) throws IOException {
        Optional<StoredFile> storedFileOpt = repo.findByDriveFileId(driveFileId);
        if (storedFileOpt.isEmpty()) {
            log.warn("Intento de eliminar archivo no encontrado en DB: {}", driveFileId);
            return;
        }
        googleDriveService.deleteFile(driveFileId);
        repo.delete(storedFileOpt.get());
        log.info("Archivo {} eliminado de Google Drive y DB.", driveFileId);
    }
}