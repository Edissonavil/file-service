package com.aec.FileSrv.controller;

import com.aec.FileSrv.Repository.StoredFileRepository;
import com.aec.FileSrv.dto.FileInfoDto;
import com.aec.FileSrv.model.StoredFile;
import com.aec.FileSrv.service.FileStorageService;
import com.aec.FileSrv.service.FileStorageService.UploadFileResponse;
import com.aec.FileSrv.service.GoogleDriveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import org.springframework.web.servlet.view.RedirectView; // Para redirección más limpia

import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {
    private final FileStorageService storage;
    private final StoredFileRepository repo;
    private final GoogleDriveService googleDriveService;
    private static final String GATEWAY_BASE = "https://gateway-production-129e.up.railway.app";

    /**
     * Paso 3.1: Generar la URL de autorización de Google Drive.
     * Este endpoint es para que **usuarios individuales** autoricen su propia cuenta de Google Drive,
     * si esa funcionalidad se mantiene para otras operaciones (ej. listar sus propios archivos).
     * Si las subidas siempre son con la cuenta de servicio (Edissonavil), este flujo es para otra cosa.
     */
    @GetMapping("/google-drive/authorize")
    // @PreAuthorize("isAuthenticated()") // Correcto, ya está comentado
    // *** INICIO DEL CAMBIO CRÍTICO ***
    public RedirectView authorizeGoogleDrive(@RequestParam(value = "userId", defaultValue = "Edissonavil") String userId) {
    // *** FIN DEL CAMBIO CRÍTICO ***
        try {
            String authorizationUrl = googleDriveService.getAuthorizationUrl(userId); // Usa el método para usuarios individuales
            log.info("Redirigiendo a la URL de autorización de Google Drive para el usuario {}: {}", userId, authorizationUrl);
            // Usamos RedirectView para una redirección más limpia, sin necesidad de ResponseEntity y HttpHeaders.LOCATION
            return new RedirectView(authorizationUrl);
        } catch (Exception e) {
            log.error("Error al generar URL de autorización de Google Drive para {}: {}", userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al iniciar autorización de Google Drive", e);
        }
    }

    /**
     * Paso 3.2: Manejar el Callback de Google después de la autorización. (Para usuarios individuales)
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
            String accessToken = googleDriveService.exchangeCodeForTokens(code, state); // Usa el método para usuarios individuales
            log.info("Tokens de Google Drive obtenidos y guardados para el usuario: {}", state);
            return ResponseEntity.ok("Autorización de Google Drive exitosa para el usuario " + state + ". Puedes cerrar esta ventana.");
        } catch (IOException e) {
            log.error("Error al intercambiar código por tokens de Google Drive para el usuario {}: {}", state, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al procesar la autorización de Google Drive", e);
        }
    }

    /**
     * 4.3: Listar archivos de Google Drive.
     * Si no se especifica un userId, lista los archivos del usuario de la API (Edissonavil).
     * Si se especifica, y el usuario ha autorizado, podría listar los suyos.
     * Esta implementación usa el servicio API por defecto.
     *
     * @param query Opcional: una consulta de búsqueda para Google Drive.
     * @param folderId Opcional: El ID de la carpeta para listar.
     * @return Una lista de información de archivos.
     */
    @GetMapping("/google-drive/list")
    //  @PreAuthorize("isAuthenticated()") // O puedes hacer que sea solo para admins si este es solo para el API user.
    public ResponseEntity<List<FileInfoDto>> listGoogleDriveFiles(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "folderId", required = false) String folderId) {
        try {
            // Usa el GoogleDriveService para el usuario API
            List<com.google.api.services.drive.model.File> googleFiles = googleDriveService.listFiles(query, folderId); // Eliminado userId
            List<FileInfoDto> dtos = googleFiles.stream()
                    .map(gf -> FileInfoDto.builder()
                            .googleDriveFileId(gf.getId())
                            .originalName(gf.getName())
                            .fileType(gf.getMimeType())
                            .size(gf.getSize())
                            .uploader("Edissonavil") // Aquí el uploader es el usuario de la API
                            .downloadUri(GATEWAY_BASE + "/api/files/download/" + gf.getId()) // URL de descarga simplificada
                            .build())
                    .collect(Collectors.toList());
            return ResponseEntity.ok(dtos);
        } catch (IOException e) {
            log.error("Error al listar archivos de Google Drive (via API user): {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al listar archivos de Google Drive", e);
        }
    }

    /**
     * Endpoint para descargar archivos de Google Drive a través de tu API.
     * Este endpoint es público y el ID de Google Drive es la clave para la descarga.
     *
     * @param googleDriveFileId El ID del archivo en Google Drive (que está guardado en StoredFile).
     * @return El archivo como un recurso descargable.
     */
    @GetMapping("/download/{googleDriveFileId}") // Nueva ruta limpia para descarga
    public ResponseEntity<Resource> downloadGoogleDriveFile(
            @PathVariable String googleDriveFileId) {
        log.info("🔍 Solicitando descarga de archivo de Google Drive por ID: {}", googleDriveFileId);
        try {
            Resource file = storage.loadAsResource(googleDriveFileId); // Eliminado uploaderId
            String contentType = storage.getFileContentType(googleDriveFileId);
            String originalName = repo.findByGoogleDriveFileId(googleDriveFileId)
                    .map(StoredFile::getOriginalName)
                    .orElse("downloaded_file");

            log.info("✅ Archivo de Google Drive encontrado: {}, tipo: {}", originalName, contentType);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + originalName + "\"")
                    .body(file);
        } catch (NoSuchFileException e) {
            log.error("❌ Archivo no encontrado en Google Drive: ID={}, Error={}", googleDriveFileId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado en Google Drive: " + googleDriveFileId, e);
        } catch (IOException e) {
            log.error("💥 Error al descargar archivo de Google Drive: ID={}, Error={}", googleDriveFileId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al descargar el archivo de Google Drive", e);
        }
    }

    // --- Endpoints para la subida y gestión de archivos persistidos (usados por otros servicios) ---

    // Este endpoint es el que ProductService llama para subir fotos/archivos
    @PostMapping(path = "/public/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadFileResponse> uploadPublic(
            @RequestPart("file") MultipartFile file,
            @RequestPart("uploader") String uploader, // Aquí el uploader se pasa explícitamente (ej. "PaulinaIsabel")
            @PathVariable Long entityId,
            @RequestParam("type") String type) throws IOException {

        log.warn("🚨 Endpoint público de subida usado. La subida se realizará con las credenciales del usuario de la API (Edissonavil). El 'uploader' ({}) se guardará como metadata.", uploader);
        UploadFileResponse response;
        switch (type.toLowerCase()) {
            case "product":
                response = storage.storeProductFile(file, uploader, entityId);
                break;
            case "receipt":
                response = storage.storeReceiptFile(file, uploader, entityId);
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido: 'product' o 'receipt'");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(path = "/receipts/{orderId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    //  @PreAuthorize("hasAuthority('ROL_CLIENTE')")
    public ResponseEntity<FileInfoDto> uploadReceiptForOrder(
            @PathVariable Long orderId,
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt) throws IOException {
        String uploader = jwt.getSubject();
        UploadFileResponse uploaded = storage.storeReceiptFile(file, uploader, orderId);
        StoredFile savedFile = repo.findByGoogleDriveFileId(uploaded.googleDriveFileId())
                .orElseThrow(() -> new IllegalStateException("Archivo guardado no encontrado en DB"));

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/download/{googleDriveFileId}")
                .buildAndExpand(savedFile.getGoogleDriveFileId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(savedFile));
    }

    @PostMapping(path = "/secure/{entityId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    //@PreAuthorize("hasAuthority('ROL_CLIENTE') or hasAuthority('ROL_COLABORADOR')")
    public ResponseEntity<FileInfoDto> uploadSecure(
            @RequestPart("file") MultipartFile file,
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long entityId,
            @RequestParam("type") String type) throws IOException {
        String uploader = jwt.getSubject();
        if (!"receipt".equalsIgnoreCase(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Solo se permite type=receipt en este endpoint.");
        }
        UploadFileResponse uploaded = storage.storeReceiptFile(file, uploader, entityId);
        StoredFile savedFile = repo.findByGoogleDriveFileId(uploaded.googleDriveFileId())
                .orElseThrow(() -> new IllegalStateException("Archivo guardado no encontrado en DB"));

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/files/download/{googleDriveFileId}")
                .buildAndExpand(savedFile.getGoogleDriveFileId())
                .toUri();
        return ResponseEntity.created(location).body(toDto(savedFile));
    }


    /**
     * Endpoint para servir archivos directamente por su ID de Google Drive.
     * Esto es para que ProductService pueda generar URLs directas en el DTO de Producto.
     * Este endpoint no es para subida, sino para visualización o descarga en línea.
     */
    @GetMapping("/{googleDriveFileId}")
    public ResponseEntity<Resource> serveFile(
            @PathVariable String googleDriveFileId) {
        log.info("🔍 Solicitando archivo para servir por ID de Google Drive: {}", googleDriveFileId);
        try {
            // El uploaderId no es necesario aquí, ya que la descarga se hace con el usuario de la API
            Resource file = storage.loadAsResource(googleDriveFileId);
            String contentType = storage.getFileContentType(googleDriveFileId);
            String originalName = repo.findByGoogleDriveFileId(googleDriveFileId)
                    .map(StoredFile::getOriginalName)
                    .orElse("downloaded_file");

            log.info("✅ Archivo para servir encontrado: {}, tipo: {}", originalName, contentType);

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


    /**
     * Endpoint para eliminar archivos de Google Drive y de la DB.
     *
     * @param googleDriveFileId El ID del archivo de Google Drive a eliminar.
     */
    @DeleteMapping("/{googleDriveFileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(
            @PathVariable String googleDriveFileId) { // Eliminado Jwt jwt, la autenticación se maneja a nivel de aplicación (Edissonavil)
        try {
            storage.deleteFile(googleDriveFileId);
            log.info("Archivo {} eliminado exitosamente.", googleDriveFileId);
        } catch (NoSuchFileException e) {
            log.error("Archivo no encontrado para eliminar: ID={}", googleDriveFileId, e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archivo no encontrado para eliminar: " + googleDriveFileId, e);
        } catch (IOException e) {
            log.error("Error al eliminar archivo: ID={}, error={}", googleDriveFileId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error al eliminar archivo", e);
        }
    }


    private FileInfoDto toDto(StoredFile sf) {
        String downloadUri = UriComponentsBuilder
                .fromHttpUrl(GATEWAY_BASE)
                .path("/api/files/download/{googleDriveFileId}")
                .buildAndExpand(sf.getGoogleDriveFileId())
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