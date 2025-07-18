package com.aec.FileSrv.controller;

import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;


@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {
    private final FileStorageService storage;

    // Endpoint unificado para servir archivos (productos o comprobantes): /api/files/{entityId}/{filename}
    // El FileStorageService determinará si es producto o comprobante internamente.

    @GetMapping("/{entityId}/{filename:.+}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable Long entityId,
            @PathVariable String filename) {
        try {
            Resource file = storage.loadAsResource(filename, entityId);
            String contentType = storage.getFileContentType(filename, entityId);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file.getFilename() + "\"")
                    .body(file);
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado: " + filename, e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al servir el archivo", e);
        }
    }
        @PostMapping(path = "/receipts/{orderId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROL_CLIENTE')")
    public ResponseEntity<FileInfoDto> uploadReceiptForOrder(
            @PathVariable Long orderId,
            @RequestPart("file") MultipartFile file,
            @RequestPart("uploader") String uploader,
            @AuthenticationPrincipal Jwt jwt
    ) throws IOException {
        StoredFile saved = storage.storeReceiptFile(file, uploader, orderId);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/{entityId}/{filename}")
                .buildAndExpand(orderId, saved.getFilename())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    // Endpoint público para subir archivos (productos o comprobantes) sin seguridad explícita
    @PostMapping(path = "/public/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileInfoDto uploadPublic(
            @RequestPart("file") MultipartFile file,
            @RequestPart("uploader") String uploader,
            @PathVariable Long entityId,
            @RequestParam("type") String type
    ) throws IOException {
        return switch (type.toLowerCase()) {
            case "product" -> toDto(storage.storeProductFile(file, uploader, entityId));
            case "receipt" -> toDto(storage.storeReceiptFile(file, uploader, entityId));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido: 'product' o 'receipt'");
        };
    }

    // Endpoint seguro para subir archivos (productos o comprobantes)
    @PostMapping(path = "/secure/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROL_CLIENTE') or hasAuthority('ROL_COLABORADOR')")
    public ResponseEntity<FileInfoDto> uploadSecure(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long entityId,
            @RequestParam("type") String type
    ) throws IOException {
        String uploader = jwt.getSubject();
        if (!"receipt".equalsIgnoreCase(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se permite type=receipt en este endpoint.");
        }
        StoredFile saved = storage.storeReceiptFile(file, uploader, entityId);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/{entityId}/{filename}")
                .buildAndExpand(entityId, saved.getFilename())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    // El método toDto se mantiene igual, ya que construye la URI de descarga de forma dinámica
    private FileInfoDto toDto(StoredFile sf) {
        String downloadUri;
        // Construye la URI de descarga basada en si es un producto o un comprobante
        if (sf.getProductId() != null) {
            downloadUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/{entityId}/{filename}") // Ahora unificado
                .buildAndExpand(sf.getProductId(), sf.getFilename())
                .toUriString();
        } else if (sf.getOrderId() != null) {
            downloadUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/{entityId}/{filename}") // Ahora unificado
                .buildAndExpand(sf.getOrderId(), sf.getFilename())
                .toUriString();
        } else {
            downloadUri = "";
            System.err.println("Advertencia: StoredFile sin productId ni orderId para generar downloadUri.");
        }

        return FileInfoDto.builder()
            .id(sf.getId())
            .filename(sf.getFilename())
            .originalName(sf.getOriginalName())
            .fileType(sf.getFileType())
            .size(sf.getSize())
            .uploader(sf.getUploader())
            .downloadUri(downloadUri)
            .build();
    }
}