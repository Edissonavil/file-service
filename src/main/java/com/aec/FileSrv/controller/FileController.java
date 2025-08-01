package com.aec.FileSrv.controller;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.service.FileStorageService;
import com.aec.FileSrv.service.GoogleDriveService;

import jakarta.servlet.http.HttpServletResponse;
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
public ResponseEntity<FileInfoDto> uploadPublic(
        @PathVariable Long entityId,
        @RequestParam("type") String type,
        @RequestParam(value = "uploader", required = false) String uploader,
        @RequestParam("file") MultipartFile file) throws IOException {

    try {
        boolean isProduct = "product".equalsIgnoreCase(type);
        FileInfoDto saved = isProduct
                ? storage.storeProductFile(file, uploader, entityId)
                : storage.storeReceiptFile(file, uploader, entityId);

        String downloadViaGateway = UriComponentsBuilder
                .fromHttpUrl(GATEWAY_BASE)
                .path("/api/files/{driveId}")
                .buildAndExpand(saved.getDriveFileId())
                .toUriString();
        saved.setDownloadUri(downloadViaGateway);
        return ResponseEntity.ok(saved);

    } catch (Exception ex) {
        log.error("ERROR subiendo comprobante para entityId=" + entityId, ex);
        throw ex;
    }
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

        @GetMapping("/{productId}/{driveId}")
        public ResponseEntity<InputStreamResource> serveFileByProduct(
                        @PathVariable Long productId,
                        @PathVariable String driveId) throws IOException {
                return serveFile(driveId);
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

        @GetMapping("/product/{productId}/zip")
        public void zipProductFiles(
                        @PathVariable Long productId,
                        HttpServletResponse response) throws IOException {

                response.setContentType("application/zip");
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"producto-" + productId + ".zip\"");

                // Delegar en un servicio que lea en Drive la carpeta productos/{productId}
                storage.streamProductZipFromDrive(productId, response.getOutputStream());
        }

        @GetMapping("/meta/product/{productId}")
        public List<FileInfoDto> getMetaByProduct(@PathVariable Long productId) {
                // Devuelve todos los archivos asociados a ese producto con su fileType y
                // originalName
                return repo.findByProductId(productId)
                                .stream()
                                .map(storage::toDto) // ya tienes toDto(StoredFile)
                                .toList();
        }

}
