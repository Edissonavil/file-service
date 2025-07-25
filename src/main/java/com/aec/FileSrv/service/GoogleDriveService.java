package com.aec.FileSrv.service;

import com.aec.FileSrv.model.UserGoogleDriveToken;
import com.aec.FileSrv.Repository.UserGoogleDriveTokenRepository;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.Details; // Import this!
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.UserCredentials;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleDriveService {

    @Value("${google.drive.client-id}")
    private String clientId;

    @Value("${google.drive.client-secret}")
    private String clientSecret;

    @Value("${google.drive.redirect-uri}")
    private String redirectUri;

    @Value("${google.drive.folder-id:}")
    private String defaultFolderId;

    @Value("${google.drive.api-user.access-token:}")
    private String apiUserAccessTokenString;

    @Value("${google.drive.api-user.refresh-token:}")
    private String apiUserRefreshToken;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private HttpTransport httpTransport;

    private final UserGoogleDriveTokenRepository tokenRepository;

    private Drive apiDriveService;

    // The constructor is handled by @RequiredArgsConstructor
    // public GoogleDriveService(UserGoogleDriveTokenRepository tokenRepository) {
    //     this.tokenRepository = tokenRepository;
    // }

    @PostConstruct
public void init() {
    try {
 // Restauramos el HttpTransport base
            this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();

            // *** TEMPORAL PARA DEPURACIÓN: Añadir interceptor de logging ***
            // La forma correcta de añadir HttpLoggingInterceptor es a través de HttpRequestInitializer
            // Necesitamos un HttpRequestInitializer que envuelva el HttpTransport.
            // Primero, aseguramos que el log level para com.google.api.client.http.HttpLoggingInterceptor
            // esté en DEBUG en tu application.yml si quieres ver los detalles.
            // logging:
            //   level:
            //     com.google.api.client.http.HttpLoggingInterceptor: DEBUG
            //
            // No se añade directamente al Builder de NetHttpTransport en la forma que lo intentaste.
            // En su lugar, el HttpLoggingInterceptor es un HttpRequestInitializer que se pasa al builder de Drive.
            // O, para un logging global, se configura a nivel de HttpTransport si el builder lo permite (NetHttpTransport.Builder().setLoggingEnabled(true) por ejemplo).
            // Para la versión que estás usando, la forma más compatible es vía HttpRequestInitializer
            // o configurando directamente el logger de Java.

            // Si quieres ver el log de bajo nivel, no necesitas cambiar el HttpTransport en sí.
            // Solo asegúrate de que el nivel de log para 'com.google.api.client.http.HttpLoggingInterceptor'
            // está en DEBUG en tu `application.yml` (como puse en el comentario del código anterior).
            // La librería de Google ya tiene un mecanismo de logging interno que se activa con niveles de DEBUG.
            // NO necesitas este bloque de código específico que te está dando error.

            // QUITAMOS LA LINEA QUE TE DIO ERROR Y MANTENEMOS EL ORIGINAL:
            // this.httpTransport = new NetHttpTransport.Builder()
            //    .addInterceptor(new HttpLoggingInterceptor(log, true))
            //    .build();

            // Mantenemos la línea original de inicialización:
            // this.httpTransport = GoogleNetHttpTransport.newTrustedTransport(); // ESTO YA ESTABA, y es lo correcto.


            if (apiUserRefreshToken != null && !apiUserRefreshToken.isEmpty()) {
                initializeApiUserDriveService();
            } else {
                log.warn("Las credenciales del usuario de la API (Edissonavil) no están completamente configuradas...");
            }
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error al inicializar HttpTransport o Drive Service para el usuario API: {}", e.getMessage());
            throw new RuntimeException("No se pudo inicializar Google Drive Service", e);
        }
    }

    private void initializeApiUserDriveService() throws IOException {
        log.info("Inicializando Google Drive Service para el usuario de la API (Edissonavil)...");

        AccessToken initialAccessToken = null;
        if (apiUserAccessTokenString != null && !apiUserAccessTokenString.isEmpty()) {
            initialAccessToken = new AccessToken(apiUserAccessTokenString, null);
        }

        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(clientId)
                .setClientSecret(clientSecret)
                .setRefreshToken(apiUserRefreshToken)
                .setAccessToken(initialAccessToken)
                .build();

        try {
            credentials.refreshAccessToken();
            log.info("Access Token del usuario de la API refrescado exitosamente al iniciar el servicio.");
            this.apiUserAccessTokenString = credentials.getAccessToken().getTokenValue();

        } catch (IOException e) {
            log.error("Error al refrescar el Access Token del usuario de la API al iniciar: {}. Asegúrate que el Refresh Token es válido. Las subidas directas no funcionarán.", e.getMessage());
            this.apiDriveService = null;
            return;
        }

        Credential apiCredential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(credentials.getAccessToken().getTokenValue());

        this.apiDriveService = new Drive.Builder(httpTransport, JSON_FACTORY, apiCredential)
                .setApplicationName("AEC File Service")
                .build();
        log.info("Servicio de Google Drive para el usuario de la API inicializado.");
    }

    // --- Métodos del flujo OAuth para usuarios individuales ---
    public String getAuthorizationUrl(String userId) {
        // --- START FIX ---
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        Details details = new Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        details.setRedirectUris(Collections.singletonList(redirectUri)); // Correct way to set redirect URIs
        clientSecrets.setWeb(details); // Set the 'web' part of the client secrets
        // --- END FIX ---

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
        // --- START FIX ---
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        Details details = new Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        details.setRedirectUris(Collections.singletonList(redirectUri)); // Correct way to set redirect URIs
        clientSecrets.setWeb(details); // Set the 'web' part of the client secrets
        // --- END FIX ---

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
    // ... (rest of your methods)
    // --- Métodos que SIEMPRE usarán la cuenta de la API (Edissonavil) ---
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