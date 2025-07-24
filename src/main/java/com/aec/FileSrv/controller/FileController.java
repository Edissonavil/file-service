package com.aec.FileSrv.controller;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.model.UserGoogleDriveToken;
import com.aec.FileSrv.service.FileStorageService;
import com.aec.FileSrv.service.GoogleDriveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {
    private final FileStorageService storage;
    private final StoredFileRepository repo; // Hazlo final si lo inyectas por constructor

    private final GoogleDriveService googleDriveService; // Inyectar GoogleDriveService
    private static final String GATEWAY_BASE = "https://gateway-production-129e.up.railway.app";

    /**
     * Paso 3.1: Generar la URL de autorización de Google Drive.
     * El frontend redirigirá al usuario a este endpoint.
     *
     * @param jwt El JWT del usuario autenticado para asociar los tokens.
     * @return Una redirección a la URL de autorización de Google.
     */
    @GetMapping("/google-drive/authorize")
    @PreAuthorize("isAuthenticated()") // Solo usuarios autenticados pueden iniciar el flujo
    public ResponseEntity<Void> authorizeGoogleDrive(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject(); // O el ID de usuario que uses
        try {
            String authorizationUrl = googleDriveService.getAuthorizationUrl(userId);
            log.info("Redirigiendo a la URL de autorización de Google Drive: {}", authorizationUrl);
            return ResponseEntity.status(HttpStatus.FOUND) // 302 Found
                    .header(HttpHeaders.LOCATION, authorizationUrl)
                    .build();
        } catch (Exception e) {
            log.error("Error al generar URL de autorización de Google Drive: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al iniciar autorización de Google Drive", e);
        }
    }

    /**
     * Paso 3.2: Manejar el Callback de Google después de la autorización.
     * Google redirigirá al usuario a este endpoint con un código de autorización.
     *
     * @param code El código de autorización de Google.
     * @param state El estado (userId) que enviamos inicialmente.
     * @return Una respuesta indicando éxito o fracaso, y quizás un mensaje para el frontend.
     */
    @GetMapping("/google-drive/oauth2callback")
    public ResponseEntity<String> oauth2Callback(@RequestParam("code") String code,
                                                 @RequestParam(value = "state", required = false) String state,
                                                 @RequestParam(value = "error", required = false) String error) {
        if (error != null) {
            log.error("Error en el callback de Google Drive: {}", error);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error en la autorización de Google Drive: " + error);
        }
        if (state == null || state.isEmpty()) {
            log.error("Callback de Google Drive sin estado (userId).");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Error de seguridad: estado no encontrado.");
        }

        try {
            String accessToken = googleDriveService.exchangeCodeForTokens(code, state);
            log.info("Tokens de Google Drive obtenidos y guardados para el usuario: {}", state);
            return ResponseEntity.ok("Autorización de Google Drive exitosa para el usuario " + state + ". Puedes cerrar esta ventana.");
        } catch (IOException e) {
            log.error("Error al intercambiar código por tokens de Google Drive para el usuario {}: {}", state, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al procesar la autorización de Google Drive", e);
        }
    }

    /**
     * 4.3: Listar archivos del usuario en Google Drive.
     *
     * @param jwt El JWT del usuario autenticado.
     * @param query Opcional: una consulta de búsqueda para Google Drive.
     * @param folderId Opcional: El ID de la carpeta para listar.
     * @return Una lista de información de archivos.
     */
    @GetMapping("/google-drive/list")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<FileInfoDto>> listGoogleDriveFiles(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "folderId", required = false) String folderId) {
        String userId = jwt.getSubject();
        try {
            List<com.google.api.services.drive.model.File> googleFiles = googleDriveService.listFiles(userId, query, folderId);
            List<FileInfoDto> dtos = googleFiles.stream()
                    .map(gf -> FileInfoDto.builder()
                            .googleDriveFileId(gf.getId())
                            .originalName(gf.getName())
                            .fileType(gf.getMimeType())
                            .size(gf.getSize())
                            .uploader(userId) // Asumimos que el uploader es el usuario actual
                            .downloadUri(GATEWAY_BASE + "/api/files/google-drive/" + gf.getId() + "/download?uploaderId=" + userId) // URI de descarga a través de tu API
                            .build())
                    .collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (IOException e) {
            log.error("Error al listar archivos de Google Drive para el usuario {}: {}", userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al listar archivos de Google Drive", e);
        }
    }

    /**
     * Endpoint para descargar archivos de Google Drive a través de tu API.
     *
     * @param googleDriveFileId El ID del archivo en Google Drive.
     * @param uploaderId El ID del usuario que subió el archivo (para obtener sus credenciales).
     * @return El archivo como un recurso descargable.
     */
    @GetMapping("/google-drive/{googleDriveFileId}/download")
    public ResponseEntity<Resource> downloadGoogleDriveFile(
            @PathVariable String googleDriveFileId,
            @RequestParam("uploaderId") String uploaderId) { // Se necesita el uploaderId para obtener las credenciales
        log.info("🔍 Solicitando descarga de archivo de Google Drive: ID={}, Uploader={}", googleDriveFileId, uploaderId);
        try {
            Resource file = storage.loadAsResource(googleDriveFileId, uploaderId);
            String contentType = storage.getFileContentType(googleDriveFileId); // Obtener el tipo MIME de la DB
            String originalName = repo.findByGoogleDriveFileId(googleDriveFileId)
                                    .map(StoredFile::getOriginalName)
                                    .orElse("downloaded_file"); // Nombre original

            log.info("✅ Archivo de Google Drive encontrado: {}, tipo: {}", originalName, contentType);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + originalName + "\"") // "attachment" para forzar descarga
                    .body(file);
        } catch (NoSuchFileException e) {
            log.error("❌ Archivo no encontrado en Google Drive: ID={}, Error={}", googleDriveFileId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en Google Drive: " + googleDriveFileId, e);
        } catch (IOException e) {
            log.error("💥 Error al descargar archivo de Google Drive: ID={}, Error={}", googleDriveFileId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al descargar el archivo de Google Drive", e);
        }
    }

    // --- Endpoints Existentes Adaptados para Google Drive ---

    @GetMapping("/{googleDriveFileId:.+}") // Cambiado el path variable
    public ResponseEntity<Resource> serveFile(
            @PathVariable String googleDriveFileId) { // Cambiado el tipo a String
        log.info("🔍 Solicitando archivo de Google Drive por ID: {}", googleDriveFileId);
        String uploaderId = repo.findByGoogleDriveFileId(googleDriveFileId)
                                .map(StoredFile::getUploader)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Uploader no encontrado para el archivo: " + googleDriveFileId));

        try {
            Resource file = storage.loadAsResource(googleDriveFileId, uploaderId);
            String contentType = storage.getFileContentType(googleDriveFileId);
            String originalName = repo.findByGoogleDriveFileId(googleDriveFileId)
                                    .map(StoredFile::getOriginalName)
                                    .orElse("downloaded_file");

            log.info("✅ Archivo encontrado: {}, tipo: {}", originalName, contentType);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + originalName + "\"")
                    .body(file);
        } catch (NoSuchFileException e) {
            log.error("❌ Archivo no encontrado: ID={}, error={}", googleDriveFileId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado: " + googleDriveFileId, e);
        } catch (Exception e) {
            log.error("💥 Error al servir archivo: ID={}, error={}", googleDriveFileId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al servir el archivo", e);
        }
    }


    @PostMapping(path = "/receipts/{orderId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROL_CLIENTE')")
    public ResponseEntity<FileInfoDto> uploadReceiptForOrder(
            @PathVariable Long orderId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) throws IOException { // uploader viene del JWT
        String uploader = jwt.getSubject();
        StoredFile saved = storage.storeReceiptFile(file, uploader, orderId);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/{googleDriveFileId}") // Usamos el ID de Google Drive
                .buildAndExpand(saved.getGoogleDriveFileId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    // Endpoint público para subir archivos (productos o comprobantes) sin seguridad explícita
    // Nota: La seguridad de Google Drive sigue aplicando por usuario.
    @PostMapping(path = "/public/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FileInfoDto uploadPublic(
            @RequestPart("file") MultipartFile file,
            @RequestPart("uploader") String uploader, // Aquí el uploader se pasa explícitamente
            @PathVariable Long entityId,
            @RequestParam("type") String type) throws IOException {
        
        log.warn("🚨 Endpoint público de subida usado. Asegúrate de que el 'uploader' ({}) tenga credenciales de Google Drive configuradas.", uploader);
        return switch (type.toLowerCase()) {
            case "product" -> toDto(storage.storeProductFile(file, uploader, entityId));
            case "receipt" -> toDto(storage.storeReceiptFile(file, uploader, entityId));
            default ->
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido: 'product' o 'receipt'");
        };
    }

    // Endpoint seguro para subir archivos (productos o comprobantes)
    @PostMapping(path = "/secure/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('ROL_CLIENTE') or hasAuthority('ROL_COLABORADOR')")
    public ResponseEntity<FileInfoDto> uploadSecure(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long entityId,
            @RequestParam("type") String type) throws IOException {
        String uploader = jwt.getSubject();
        if (!"receipt".equalsIgnoreCase(type)) { // Solo comprobantes en este endpoint
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se permite type=receipt en este endpoint.");
        }
        StoredFile saved = storage.storeReceiptFile(file, uploader, entityId);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/{googleDriveFileId}") // Usamos el ID de Google Drive
                .buildAndExpand(saved.getGoogleDriveFileId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(saved));
    }

    private FileInfoDto toDto(StoredFile sf) {
        String downloadUri;
        downloadUri = UriComponentsBuilder
                .fromHttpUrl(GATEWAY_BASE)
                .path("/api/files/{googleDriveFileId}")
                .toUriString();

        return FileInfoDto.builder()
                .id(sf.getId())
                .googleDriveFileId(sf.getGoogleDriveFileId()) 
                .originalName(sf.getOriginalName())
                .fileType(sf.getFileType())
                .size(sf.getSize())
                .uploader(sf.getUploader())
                .downloadUri(downloadUri)
                .build();
    }
}