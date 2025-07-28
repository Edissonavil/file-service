package com.aec.FileSrv.controller;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.service.FileStorageService;
import com.aec.FileSrv.service.GoogleDriveService;

import lombok.RequiredArgsConstructor;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.multipart.MultipartFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService storage;
    private final StoredFileRepository repo;
    private final GoogleDriveService drive;
    private final Logger log = LoggerFactory.getLogger(FileController.class);

    private static final String GATEWAY_BASE = "https://gateway-production-129e.up.railway.app";

    @PostMapping(path = "/public/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileInfoDto uploadPublic(
            @PathVariable Long entityId,
            @RequestPart("file") MultipartFile file,
            @RequestParam("type") String type,
            @RequestParam(value = "uploader", required = false) String uploader
    ) throws IOException {

        boolean isProduct = "product".equalsIgnoreCase(type);

        FileInfoDto saved = isProduct
                ? storage.storeProductFile(file, uploader, entityId)
                : storage.storeReceiptFile(file, uploader, entityId);

           log.info("uploadPublic -> pre-build dto: id={}, driveFileId={}, filename={}",
            saved.getId(), saved.getDriveFileId(), saved.getFilename());

        String downloadViaGateway = UriComponentsBuilder
                .fromHttpUrl(GATEWAY_BASE)
                .path("/api/files/{driveId}")
                .buildAndExpand(saved.getDriveFileId())
                .toUriString();

        saved.setDownloadUri(downloadViaGateway);
        return saved;
    }

    @GetMapping("/{driveId}")
    public ResponseEntity<InputStreamResource> serveFile(@PathVariable String driveId) throws IOException {
        StoredFile sf = repo.findByDriveFileId(driveId).orElse(null);
        InputStream in = storage.loadAsResource(driveId).getInputStream();
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

        return drive.listFiles(isProduct, size)
                .stream()
                .map(f -> FileInfoDto.builder()
                        .filename(f.getName())
                        .driveFileId(f.getId())
                        .downloadUri("https://drive.google.com/file/d/" + f.getId() + "/view")
                        .build())
                .collect(Collectors.toList());
    }

    @DeleteMapping("/{driveId}")
    @PreAuthorize("hasAnyAuthority('ROL_COLABORADOR','ROL_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable String driveId) throws IOException {
        storage.deleteFile(driveId);
        return ResponseEntity.noContent().build();
    }
}
