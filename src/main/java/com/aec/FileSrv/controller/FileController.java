package com.aec.FileSrv.controller;

import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.service.GoogleDriveService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final GoogleDriveService driveSvc;
    private final StoredFileRepository repo;

    private static final String GATEWAY_BASE = "https://gateway-production-129e.up.railway.app";

    @PostMapping(path = "/public/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileInfoDto uploadPublic(
        @PathVariable Long entityId,
        @RequestPart("file") MultipartFile file,
        @RequestParam("type") String type,
        @RequestParam(value = "uploader", required = false) String uploader
    ) throws IOException {
        boolean isProduct = "product".equalsIgnoreCase(type);
        String driveId = driveSvc.uploadFile(file, isProduct);

        StoredFile sf = new StoredFile();
        sf.setFilename(file.getOriginalFilename());
        sf.setOriginalName(file.getOriginalFilename());
        sf.setFileType(file.getContentType() != null ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        sf.setSize(file.getSize());
        sf.setUploader(uploader != null ? uploader : "public");
        sf.setUploadedAt(Instant.now());
        if (isProduct) sf.setProductId(entityId); else sf.setOrderId(entityId);
        sf.setDriveFileId(driveId);
        sf = repo.save(sf);

        String downloadViaGateway = GATEWAY_BASE + "/api/files/" + driveId;

        return FileInfoDto.builder()
            .id(sf.getId())
            .filename(sf.getFilename())
            .originalName(sf.getOriginalName())
            .fileType(sf.getFileType())
            .size(sf.getSize())
            .uploader(sf.getUploader())
            .driveFileId(driveId)
            .downloadUri(downloadViaGateway)
            .build();
    }

    @GetMapping("/{driveId}")
    public ResponseEntity<InputStreamResource> serveFile(@PathVariable String driveId) throws IOException {
        StoredFile sf = repo.findByDriveFileId(driveId).orElse(null);
        InputStream in = driveSvc.downloadFile(driveId);
        MediaType type = (sf != null && sf.getFileType() != null)
            ? MediaType.parseMediaType(sf.getFileType())
            : MediaType.APPLICATION_OCTET_STREAM;
        String filename = (sf != null) ? sf.getFilename() : driveId;

        return ResponseEntity.ok()
            .contentType(type)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
            .body(new InputStreamResource(in));
    }

    @GetMapping(path = "/list/{type}")
    public List<FileInfoDto> listFiles(
        @PathVariable String type,
        @RequestParam(defaultValue = "100") int size) throws IOException {
        boolean isProduct = "product".equalsIgnoreCase(type);
        return driveSvc.listFiles(isProduct, size).stream()
            .map(f -> FileInfoDto.builder()
                   .filename(f.getName())
                   .downloadUri("https://drive.google.com/file/d/" + f.getId() + "/view")
                   .driveFileId(f.getId())
                   .build())
            .collect(Collectors.toList());
    }
}