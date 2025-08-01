package com.aec.FileSrv.service;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.drive.DriveFile;
import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.google.api.client.util.Value;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okio.internal.ZipEntry;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Optional;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class FileStorageService {

    private final StoredFileRepository repo;
    private final GoogleDriveService drive;
    @Value("${file-service.gateway-base-url}")
    private String gatewayBaseUrl;

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
        try {
            // Validaciones
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("El archivo no puede estar vacío");
            }
            if (productId == null) {
                throw new IllegalArgumentException("El productId no puede ser null");
            }

            // Crear estructura de carpetas
            String root = drive.getOrCreateFolder("productos", null);
            String folder = drive.getOrCreateFolder(String.valueOf(productId), root);

            // Subir archivo
            String driveId = drive.uploadFileToFolder(file, folder);

            // Buscar si ya existe
            Optional<StoredFile> existingStoredFile = repo.findByProductIdAndFilename(productId,
                    file.getOriginalFilename());

            StoredFile sf;
            if (existingStoredFile.isPresent()) {
                sf = existingStoredFile.get();
                // Actualizar solo los campos que podrían cambiar
                sf.setDriveFileId(driveId);
                sf.setFileType(file.getContentType());
                sf.setSize(file.getSize());
                sf.setUploader(uploader != null ? uploader : "public");
                sf.setUploadedAt(Instant.now());
                log.info("Actualizando archivo existente para producto {}: {}", productId, file.getOriginalFilename());
            } else {
                // Si no existe, crear un nuevo StoredFile
                sf = new StoredFile();
                sf.setDriveFileId(driveId);
                sf.setFilename(file.getOriginalFilename());
                sf.setOriginalName(file.getOriginalFilename());
                sf.setFileType(file.getContentType());
                sf.setSize(file.getSize());
                sf.setUploader(uploader != null ? uploader : "public");
                sf.setUploadedAt(Instant.now());
                sf.setProductId(productId);
                sf.setOrderId(null);
                log.info("Creando nuevo archivo para producto {}: {}", productId, file.getOriginalFilename());
            }

            return toDto(repo.save(sf));
        } catch (Exception e) {
            log.error("Error almacenando archivo de producto {}: {}", productId, e.getMessage(), e);
            throw new IOException("Error almacenando archivo de producto: " + e.getMessage(), e);
        }
    }

    public FileInfoDto storeReceiptFile(MultipartFile file, String uploader, Long orderId) throws IOException {
        try {
            // Validaciones
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("El archivo no puede estar vacío");
            }
            if (orderId == null) {
                throw new IllegalArgumentException("El orderId no puede ser null");
            }

            // Crear estructura de carpetas
            String root = drive.getOrCreateFolder("comprobantes", null);
            String folder = drive.getOrCreateFolder(String.valueOf(orderId), root);

            // Subir archivo
            String driveId = drive.uploadFileToFolder(file, folder);

            // Buscar si ya existe
            Optional<StoredFile> existingStoredFile = repo.findByOrderIdAndFilename(orderId,
                    file.getOriginalFilename());

            StoredFile sf;
            if (existingStoredFile.isPresent()) {
                sf = existingStoredFile.get();
                sf.setDriveFileId(driveId);
                sf.setFileType(file.getContentType());
                sf.setSize(file.getSize());
                sf.setUploader(uploader != null ? uploader : "public");
                sf.setUploadedAt(Instant.now());
                log.info("Actualizando comprobante existente para orden {}: {}", orderId, file.getOriginalFilename());
            } else {
                sf = new StoredFile();
                sf.setDriveFileId(driveId);
                sf.setFilename(file.getOriginalFilename());
                sf.setOriginalName(file.getOriginalFilename());
                sf.setFileType(file.getContentType());
                sf.setSize(file.getSize());
                sf.setUploader(uploader != null ? uploader : "public");
                sf.setUploadedAt(Instant.now());
                sf.setProductId(null);
                sf.setOrderId(orderId);
                log.info("Creando nuevo comprobante para orden {}: {}", orderId, file.getOriginalFilename());
            }

            return toDto(repo.save(sf));
        } catch (Exception e) {
            log.error("Error almacenando comprobante para orden {}: {}", orderId, e.getMessage(), e);
            throw new IOException("Error almacenando comprobante: " + e.getMessage(), e);
        }
    }

    public void streamProductZipFromDrive(Long productId, OutputStream os) throws IOException {
        try {
            // Usar el método correcto para rutas con "/"
            String folderPath = "productos/" + productId;
            String folderId = drive.getOrCreateFolderByPath(folderPath);

            var files = drive.listFilesInFolder(folderId);

            try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os))) {
                boolean added = false;

                for (DriveFile f : files) {
                    added = true;
                    zos.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
                    try (InputStream in = drive.downloadFile(f.getId())) {
                        in.transferTo(zos);
                    }
                    zos.closeEntry();
                }

                if (!added) {
                    throw new IOException("No hay archivos descargables en carpeta: " + folderPath);
                }
                zos.finish();
            }
        } catch (Exception e) {
            log.error("Error creando ZIP para producto {}: {}", productId, e.getMessage(), e);
            throw new IOException("Error creando ZIP: " + e.getMessage(), e);
        }
    }

    private StoredFile saveStoredFile(MultipartFile file, String uploader,
            Long productId, Long orderId, String driveId) throws IOException {

        StoredFile sf = new StoredFile();
        sf.setDriveFileId(driveId);
        sf.setFilename(file.getOriginalFilename());
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
                log.info("Archivo eliminado de Drive: {}", driveFileId);
            } catch (IOException e) {
                log.warn("No se pudo borrar en Drive {}: {}", driveFileId, e.getMessage());
            }
            repo.delete(sf);
            log.info("Archivo eliminado de BD: {}", driveFileId);
        });
    }

    public FileInfoDto toDto(StoredFile sf) {
        String downloadUri = UriComponentsBuilder
                .fromHttpUrl(gatewayBaseUrl)
                .path("/api/files/{driveId}")
                .buildAndExpand(sf.getDriveFileId())
                .toUriString();

        return FileInfoDto.builder()
                .id(sf.getId())
                .driveFileId(sf.getDriveFileId())
                .filename(sf.getFilename())
                .originalName(sf.getOriginalName())
                .fileType(sf.getFileType())
                .size(sf.getSize())
                .uploader(sf.getUploader())
                .downloadUri(downloadUri)
                .build();
    }
}