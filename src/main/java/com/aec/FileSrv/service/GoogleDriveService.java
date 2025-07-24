package com.aec.FileSrv.service;

import com.aec.FileSrv.Repository.UserGoogleDriveTokenRepository;
import com.aec.FileSrv.model.UserGoogleDriveToken;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.UserCredentials;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.io.InputStream;
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

    // Asegúrate de que este ID de carpeta sea correcto y que el API user tenga acceso
    @Value("${google.drive.folder-id:}") 
    private String defaultFolderId;

    // --- TEMPORAL: Hacemos que estas variables sean opcionales para que el servicio arranque ---
    // Usamos :'' para que si no están definidas, el valor por defecto sea una cadena vacía,
    // evitando el error 'Could not resolve placeholder'.
    @Value("${google.drive.api-user.access-token:}") 
    private AccessToken apiUserAccessToken;

    @Value("${google.drive.api-user.refresh-token:}") 
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
            // Solo intentar inicializar el servicio de API user si los tokens están presentes.
            // Esto permite que el servicio arranque incluso si las variables de entorno aún no están configuradas.
            if (
                apiUserRefreshToken != null && !apiUserRefreshToken.isEmpty()) {
                initializeApiUserDriveService();
            } else {
                log.warn("Las credenciales del usuario de la API (Edissonavil) no están completamente configuradas. Las operaciones que dependen de ellas (subida/descarga/listado directo) no funcionarán hasta que se configuren.");
            }
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error al inicializar HttpTransport o Drive Service para el usuario API: {}", e.getMessage());
            throw new RuntimeException("No se pudo inicializar Google Drive Service", e);
        }
    }

    private void initializeApiUserDriveService() throws IOException {
        // No verificamos si los tokens están vacíos aquí, ya que el @PostConstruct lo hizo.
        // Aquí asumimos que apiUserAccessToken y apiUserRefreshToken ya tienen un valor.
        log.info("Inicializando Google Drive Service para el usuario de la API (Edissonavil)...");

        UserCredentials credentials = UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(apiUserRefreshToken)
            .setAccessToken(apiUserAccessToken) 
            .build();

        try {
            credentials.refreshAccessToken(); 
            log.info("Access Token del usuario de la API refrescado exitosamente al iniciar el servicio.");
        } catch (IOException e) {
            log.error("Error al refrescar el Access Token del usuario de la API al iniciar: {}. Asegúrate que el Refresh Token es válido. Las subidas directas no funcionarán.", e.getMessage());
            // No lanzamos la excepción aquí, solo loggeamos, para permitir que el servicio arranque.
            // Pero las operaciones de Drive que usen apiDriveService fallarán.
            return; // Salimos si el refresh falla. apiDriveService seguirá siendo null.
        }

        Credential apiCredential = new Credential(BearerToken.authorizationHeaderAccessMethod())
            .setAccessToken(credentials.getAccessToken().getTokenValue());
        
        this.apiDriveService = new Drive.Builder(httpTransport, JSON_FACTORY, apiCredential)
                .setApplicationName("AEC File Service")
                .build();
        log.info("Servicio de Google Drive para el usuario de la API inicializado.");
    }

    // --- Métodos del flujo OAuth para usuarios individuales (NO COMENTAR ESTOS) ---
    // Estos son necesarios para que puedas obtener los tokens de Edissonavil.
    public String getAuthorizationUrl(String userId) {
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        clientSecrets.setWeb(details);
        
        List<String> scopes = Collections.singletonList(DriveScopes.DRIVE_FILE);

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, scopes)
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();
        
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(userId) 
                .setAccessType("offline")
                .build();
    }

    @Transactional
    public String exchangeCodeForTokens(String code, String state) throws IOException {
        String userId = state; 
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        clientSecrets.setWeb(details);
        
        List<String> scopes = Collections.singletonList(DriveScopes.DRIVE_FILE);

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, scopes)
                .setAccessType("offline")
                .build();

        GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();

        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setFromTokenResponse(tokenResponse);

        UserGoogleDriveToken userToken = tokenRepository.findByUserId(userId)
                .orElse(new UserGoogleDriveToken());

        userToken.setUserId(userId);
        userToken.setAccessToken(credential.getAccessToken());
        userToken.setRefreshToken(credential.getRefreshToken());
        userToken.setTokenCreationTime(Instant.now());
        userToken.setExpiresInSeconds(credential.getExpiresInSeconds());
        userToken.setScope(String.join(" ", Collections.singletonList(DriveScopes.DRIVE_FILE))); 

        tokenRepository.save(userToken);
        log.info("Tokens de Google Drive guardados para el usuario: {}", userId);
        return credential.getAccessToken();
    }
    // FIN: Métodos del flujo OAuth para usuarios individuales

    // --- Métodos que SIEMPRE usarán la cuenta de la API (Edissonavil) ---
    // NO COMENTAR ESTOS. Si apiDriveService es null (porque los tokens no se configuraron al inicio),
    // estos métodos lanzarán una IllegalStateException, que es el comportamiento esperado
    // hasta que los tokens se configuren correctamente.
    public String uploadFile(InputStream fileContent, String mimeType, String fileName, String parentFolderId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales.");
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

    public InputStream downloadFile(String fileId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales.");
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        this.apiDriveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        log.info("Archivo descargado de Google Drive (vía API user): ID={}", fileId);
        return new java.io.ByteArrayInputStream(outputStream.toByteArray());
    }

    public List<com.google.api.services.drive.model.File> listFiles(String query, String folderId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales.");
        }
        Drive.Files.List request = this.apiDriveService.files().list()
                .setFields("nextPageToken, files(id, name, mimeType, size, createdTime, modifiedTime, parents)");
        String effectiveFolderId = (folderId != null && !folderId.isEmpty()) ? folderId : defaultFolderId;
        if (effectiveFolderId != null && !effectiveFolderId.isEmpty()) {
            request.setQ("'" + effectiveFolderId + "' in parents and trashed = false" + (query != null && !query.isEmpty() ? " and " + query : ""));
        } else if (query != null && !query.isEmpty()) {
            request.setQ(query + " and trashed = false");
        } else {
            request.setQ("'root' in parents and trashed = false");
        }
        FileList files = request.execute();
        log.info("Archivos listados (vía API user): {} resultados", files.getFiles().size());
        return files.getFiles();
    }

    public void deleteFile(String fileId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales.");
        }
        this.apiDriveService.files().delete(fileId).execute();
        log.info("Archivo eliminado de Google Drive (vía API user): ID={}", fileId);
    }
}