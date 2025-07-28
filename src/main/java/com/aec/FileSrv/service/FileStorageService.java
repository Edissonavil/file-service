package com.aec.FileSrv.service;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private final StoredFileRepository repo;
    private final GoogleDriveService drive;

    public Resource loadAsResource(String driveFileId) throws IOException {
        Optional<StoredFile> sf = repo.findByDriveFileId(driveFileId);
        if (sf.isEmpty()) {
            throw new java.nio.file.NoSuchFileException("No existe en DB: " + driveFileId);
        }
        InputStream in = drive.downloadFile(driveFileId);
        return new InputStreamResource(in);
    }

    public String getFileContentType(String driveFileId) {
        return repo.findByDriveFileId(driveFileId)
                .map(StoredFile::getFileType)
                .orElse("application/octet-stream");
    }

    public FileInfoDto storeProductFile(MultipartFile file, String uploader, Long productId) throws IOException {
    String driveId = drive.uploadFile(file, true);
    log.info("storeProductFile -> subido a Drive. driveId={}", driveId);
    StoredFile sf = saveStoredFile(file, uploader, productId, null, driveId);
    log.info("storeProductFile -> guardado DB. storedFile.id={}, driveId={}", sf.getId(), sf.getDriveFileId());
    return toDto(sf);
}


    public FileInfoDto storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        String driveId = drive.uploadFile(file, false);
        StoredFile sf = saveStoredFile(file, uploader, null, orderId, driveId);

        return toDto(sf);
    }

    private StoredFile saveStoredFile(MultipartFile file, String uploader,
                                      Long productId, Long orderId, String driveId) throws IOException {

        StoredFile sf = new StoredFile();
        sf.setDriveFileId(driveId);
        sf.setFilename(file.getOriginalFilename());   // “lógico”
        sf.setOriginalName(file.getOriginalFilename());
        sf.setFileType(file.getContentType());
        sf.setSize(file.getSize());
        sf.setUploader(uploader != null ? uploader : "public");
        sf.setUploadedAt(Instant.now());
        sf.setProductId(productId);
        sf.setOrderId(orderId);

        return repo.save(sf);
    }

    public void deleteFile(String driveFileId) throws IOException {
        repo.findByDriveFileId(driveFileId).ifPresent(sf -> {
            try {
                drive.deleteFile(driveFileId);
            } catch (IOException e) {
                log.warn("No se pudo borrar en Drive {}: {}", driveFileId, e.getMessage());
            }
            repo.delete(sf);
        });
    }

    public FileInfoDto toDto(StoredFile sf) {
        return FileInfoDto.builder()
                .id(sf.getId())
                .driveFileId(sf.getDriveFileId())
                .filename(sf.getFilename())
                .originalName(sf.getOriginalName())
                .fileType(sf.getFileType())
                .size(sf.getSize())
                .uploader(sf.getUploader())
                .build();
    }
}
