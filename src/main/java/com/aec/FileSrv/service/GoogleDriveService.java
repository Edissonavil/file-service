package com.aec.FileSrv.service;

import com.aec.FileSrv.Repository.UserGoogleDriveTokenRepository;
import com.aec.FileSrv.model.UserGoogleDriveToken;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.oauth2.UserCredentials;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class GoogleDriveService {

    @Value("${google.drive.client-id}")
    private String clientId;

    @Value("${google.drive.client-secret}")
    private String clientSecret;

    @Value("${google.drive.redirect-uri}")
    private String redirectUri;

    @Value("${google.drive.folder-id:}")
    private String defaultFolderId;

    // --- Credenciales para el usuario de la API (Edissonavil) ---
    // Ahora hacemos que estas propiedades sean opcionales usando Optional<String>
    // y asignando valores predeterminados vacíos si no se encuentran.
    @Value("${google.drive.api-user.access-token:#{null}}") // Usar SpEL para null si no está presente
    private String apiUserAccessToken;

    @Value("${google.drive.api-user.refresh-token:#{null}}") // Usar SpEL para null si no está presente
    private String apiUserRefreshToken;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private HttpTransport httpTransport;

    private final UserGoogleDriveTokenRepository tokenRepository;

    private Drive apiDriveService;

    public GoogleDriveService(UserGoogleDriveTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @PostConstruct
    public void init() {
        try {
            this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            // Solo intenta inicializar el servicio de Drive para el usuario API
            // si las credenciales están presentes.
            if (apiUserAccessToken != null && !apiUserAccessToken.isEmpty() &&
                apiUserRefreshToken != null && !apiUserRefreshToken.isEmpty()) {
                initializeApiUserDriveService();
            } else {
                log.warn("Las credenciales del usuario de la API (Edissonavil) no están configuradas en las variables de entorno. Las subidas directas no funcionarán hasta que se configuren.");
            }
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error al inicializar HttpTransport o Drive Service para el usuario API: {}", e.getMessage());
            // No lanzamos RuntimeException aquí para permitir que el servicio se inicie
            // si el problema es solo la falta de credenciales de Google Drive.
            // Si el HttpTransport falla, sí que es crítico.
            if (e instanceof GeneralSecurityException) {
                throw new RuntimeException("No se pudo inicializar Google Drive Service debido a un problema de seguridad en HttpTransport", e);
            }
            // Si el error es de IO (ej. refresh token inválido), logueamos pero no impedimos el inicio
            // ya que la aplicación podría funcionar para otras cosas.
        }
    }

    private void initializeApiUserDriveService() throws IOException {
        // Esta comprobación ya se hace en init(), pero se mantiene para claridad.
        if (apiUserAccessToken == null || apiUserAccessToken.isEmpty() ||
            apiUserRefreshToken == null || apiUserRefreshToken.isEmpty()) {
            log.warn("initializeApiUserDriveService() llamado sin credenciales. Esto no debería pasar.");
            return; // No intentar inicializar si no hay tokens.
        }

        UserCredentials credentials = UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(apiUserRefreshToken)
            .build();

        try {
            // Este refresh es crucial. Si el refresh token es inválido, fallará aquí.
            credentials.refreshAccessToken();
            log.info("Access Token del usuario de la API refrescado exitosamente al iniciar el servicio.");

            Credential apiCredential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(credentials.getAccessToken().getTokenValue());

            this.apiDriveService = new Drive.Builder(httpTransport, JSON_FACTORY, apiCredential)
                    .setApplicationName("AEC File Service")
                    .build();
            log.info("Servicio de Google Drive para el usuario de la API inicializado.");
        } catch (IOException e) {
            log.error("Error al refrescar o inicializar Drive Service con el usuario API: {}. Asegúrate que el Refresh Token es válido.", e.getMessage());
            // No lanzar excepción aquí. Permitir que el servicio de archivos se inicie,
            // pero las operaciones de Google Drive con el usuario API fallarán hasta que se resuelva.
            this.apiDriveService = null; // Asegurar que no hay una instancia válida
            throw e; // Lanzar la excepción para que sea visible en los logs y se sepa que hay un problema.
        }
    }

    // ... (rest of your GoogleDriveService class methods remain the same) ...

    /**
     * Sube un archivo a Google Drive usando las credenciales del usuario de la API (Edissonavil).
     *
     * @param fileContent El InputStream del archivo.
     * @param mimeType El tipo MIME del archivo.
     * @param fileName El nombre del archivo en Google Drive.
     * @param parentFolderId El ID de la carpeta donde subir el archivo. Si es null o vacío, se sube a la carpeta por defecto.
     * @return El ID del archivo creado en Google Drive.
     * @throws IOException Si ocurre un error al subir el archivo.
     */
    public String uploadFile(InputStream fileContent, String mimeType, String fileName, String parentFolderId) throws IOException {
        if (this.apiDriveService == null) {
            // Si el servicio de Drive no está inicializado (ej. por falta de tokens o tokens inválidos)
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado o sus credenciales son inválidas. Por favor, configure los tokens de acceso y refresco para Edissonavil.");
        }

        File fileMetadata = new File();
        fileMetadata.setName(fileName);

        String targetFolderId = (parentFolderId != null && !parentFolderId.isEmpty()) ? parentFolderId : defaultFolderId;
        if (targetFolderId != null && !targetFolderId.isEmpty()) {
            fileMetadata.setParents(Collections.singletonList(targetFolderId));
        }

        InputStreamContent mediaContent = new InputStreamContent(mimeType, fileContent);
        File uploadedFile = this.apiDriveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType")
                .execute();

        log.info("Archivo subido a Google Drive (vía API user): ID={}, Nombre={}", uploadedFile.getId(), uploadedFile.getName());
        return uploadedFile.getId();
    }

    // Los métodos downloadFile, listFiles, deleteFile también deberían tener la misma comprobación inicial
    // para apiDriveService == null y lanzar un IllegalStateException si no está disponible.
    // Esto asegura que, aunque el servicio inicie, las operaciones que dependen de Google Drive fallen explícitamente.
/*
    public InputStream downloadFile(String fileId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales o son inválidas.");
        }
        // ... (resto del código)
    }*/

   /*  public List<com.google.api.services.drive.model.File> listFiles(String query, String folderId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales o son inválidas.");
        }
        // ... (resto del código)
    }*/

    public void deleteFile(String fileId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales o son inválidas.");
        }
        // ... (resto del código)
    }
}