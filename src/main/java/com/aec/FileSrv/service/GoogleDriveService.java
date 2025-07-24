package com.aec.FileSrv.service;

import com.aec.FileSrv.Repository.UserGoogleDriveTokenRepository; // Aún la necesitamos para el flujo de autorización de usuarios individuales
import com.aec.FileSrv.model.UserGoogleDriveToken; // Para el flujo de autorización

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory; // Usa GsonFactory si prefieres
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.oauth2.UserCredentials; // Para el refresco de tokens, si se usa con flow
import com.google.api.client.auth.oauth2.TokenResponse;

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

    @Value("${google.drive.folder-id:}") // Carpeta por defecto donde se subirán los archivos
    private String defaultFolderId;

    // --- Credenciales para el usuario de la API (Edissonavil) ---
    // Estas credenciales deben ser obtenidas UNA SOLA VEZ a través del flujo OAuth
    // para Edissonavil y luego guardadas de forma segura (ej. en variables de entorno o un vault).
    @Value("${google.drive.api-user.access-token}")
    private String apiUserAccessToken;

    @Value("${google.drive.api-user.refresh-token}")
    private String apiUserRefreshToken;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private HttpTransport httpTransport;

    private final UserGoogleDriveTokenRepository tokenRepository; // Todavía necesaria para los usuarios individuales que quieran enlazar su propio Drive (si esa funcionalidad se mantiene).
    
    // Instancia de Drive Service para el usuario de la API (Edissonavil)
    private Drive apiDriveService;

    public GoogleDriveService(UserGoogleDriveTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
    }

    @PostConstruct // Se ejecuta después de que las propiedades @Value son inyectadas
    public void init() {
        try {
            this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
            // Inicializar el Drive Service para el usuario de la API (Edissonavil)
            initializeApiUserDriveService();
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error al inicializar HttpTransport o Drive Service para el usuario API: {}", e.getMessage());
            throw new RuntimeException("No se pudo inicializar Google Drive Service", e);
        }
    }

    private void initializeApiUserDriveService() throws IOException {
        if (apiUserAccessToken == null || apiUserAccessToken.isEmpty() || apiUserRefreshToken == null || apiUserRefreshToken.isEmpty()) {
            log.warn("Las credenciales del usuario de la API (Edissonavil) no están configuradas en las variables de entorno. Las subidas directas no funcionarán.");
            // Si no están configuradas, el servicio no podrá subir archivos como Edissonavil.
            // Puedes lanzar una excepción aquí si es un requisito estricto.
            return;
        }

        // Construir la credencial para el usuario de la API (Edissonavil)
        // Usamos com.google.auth.oauth2.UserCredentials para gestionar el refresco.
        UserCredentials credentials = UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(apiUserRefreshToken)
            .setAccessToken(new com.google.auth.oauth2.AccessToken(apiUserAccessToken, null)) // También puedes establecer el access token inicial
            .build();

        // Refrescar el token al inicio para asegurar que esté válido
        try {
            credentials.refreshAccessToken(); // Forzar un refresh para obtener un token de acceso fresco
            log.info("Access Token del usuario de la API refrescado exitosamente al iniciar el servicio.");
        } catch (IOException e) {
            log.error("Error al refrescar el Access Token del usuario de la API al iniciar: {}. Asegúrate que el Refresh Token es válido.", e.getMessage());
            // Si el refresco falla aquí, el servicio no podrá operar con Drive.
            throw e; // Relanza para que la aplicación no se inicie si las credenciales son inválidas.
        }

        // Crear una instancia de Credential de google.api.client.auth.oauth2
        // para el Builder de Drive, usando el access token refrescado.
        Credential apiCredential = new Credential(BearerToken.authorizationHeaderAccessMethod())
            .setAccessToken(credentials.getAccessToken().getTokenValue());
        
        // Puedes agregar lógica para refrescar periódicamente o al fallar.
        // Pero para un "usuario de la API" estático, UserCredentials lo gestiona.

        this.apiDriveService = new Drive.Builder(httpTransport, JSON_FACTORY, apiCredential)
                .setApplicationName("AEC File Service")
                .build();
        log.info("Servicio de Google Drive para el usuario de la API inicializado.");
    }

    // --- Métodos del flujo OAuth para usuarios individuales (si se mantiene) ---
    // Estos son para que CADA usuario pueda enlazar su propia cuenta de Drive.
    // Si no necesitas que los colaboradores tengan su propio Drive, estos métodos
    // y UserGoogleDriveTokenRepository pueden ser eliminados.
    // Asumo que los mantienes para un posible futuro donde un usuario QUIERA
    // listar o gestionar sus propios archivos de Drive.

    public String getAuthorizationUrl(String userId) {
        GoogleAuthorizationCodeFlow flow = buildFlowForIndividualUser();
        String state = userId;
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(state)
                .setAccessType("offline")
                .build();
    }

    @Transactional
    public String exchangeCodeForTokens(String code, String state) throws IOException {
        String userId = state;
        GoogleAuthorizationCodeFlow flow = buildFlowForIndividualUser();
        TokenResponse tokenResponse = flow.newTokenRequest(code)
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

    // Este método es para obtener credenciales de usuarios INDIVIDUALES
    @Transactional
    public Credential getCredentialForIndividualUser(String userId) throws IOException {
        Optional<UserGoogleDriveToken> userTokenOpt = tokenRepository.findByUserId(userId);
        if (userTokenOpt.isEmpty()) {
            throw new IOException("No hay tokens de Google Drive para el usuario: " + userId);
        }

        UserGoogleDriveToken userToken = userTokenOpt.get();

        long expiresInMillis = userToken.getTokenCreationTime().toEpochMilli() + userToken.getExpiresInSeconds() * 1000;

        if (System.currentTimeMillis() >= expiresInMillis - (60 * 1000) && userToken.getRefreshToken() != null) {
            log.info("Intentando refrescar token de acceso para el usuario: {}", userId);
            try {
                UserCredentials creds = UserCredentials.newBuilder()
                    .setClientId(clientId)
                    .setClientSecret(clientSecret)
                    .setRefreshToken(userToken.getRefreshToken())
                    .build();

                creds.refresh();

                userToken.setAccessToken(creds.getAccessToken().getTokenValue());
                userToken.setTokenCreationTime(Instant.ofEpochMilli(creds.getAccessToken().getExpirationTime().getTime()));
                userToken.setExpiresInSeconds((creds.getAccessToken().getExpirationTime().getTime() - Instant.now().toEpochMilli()) / 1000);
                tokenRepository.save(userToken);
                log.info("Token de acceso refrescado y guardado para el usuario: {}", userId);

                return new Credential(BearerToken.authorizationHeaderAccessMethod())
                    .setAccessToken(userToken.getAccessToken())
                    .setRefreshToken(userToken.getRefreshToken())
                    .setExpirationTimeMilliseconds(userToken.getTokenCreationTime().toEpochMilli() + userToken.getExpiresInSeconds() * 1000);

            } catch (Exception e) {
                log.warn("No se pudo refrescar el token para el usuario: {}. Error: {}", userId, e.getMessage());
                throw new IOException("No se pudo refrescar el token de acceso. Reautenticación necesaria.", e);
            }
        } else if (System.currentTimeMillis() >= expiresInMillis && userToken.getRefreshToken() == null) {
            log.warn("Token expirado y sin refresh token para el usuario: {}. Necesita reautenticarse.", userId);
            throw new IOException("Token expirado y sin refresh token. Reautenticación necesaria.");
        } else if (System.currentTimeMillis() >= expiresInMillis && userToken.getRefreshToken() != null && userToken.getAccessToken() == null) {
            log.warn("Token de acceso ausente pero con refresh token para el usuario: {}. Forzando reautenticación.", userId);
            throw new IOException("Token de acceso ausente. Reautenticación necesaria.");
        }

        return new Credential(BearerToken.authorizationHeaderAccessMethod())
            .setAccessToken(userToken.getAccessToken())
            .setRefreshToken(userToken.getRefreshToken())
            .setExpirationTimeMilliseconds(userToken.getTokenCreationTime().toEpochMilli() + userToken.getExpiresInSeconds() * 1000);
    }

    private GoogleAuthorizationCodeFlow buildFlowForIndividualUser() {
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        clientSecrets.setWeb(details);

        List<String> scopes = Collections.singletonList(DriveScopes.DRIVE_FILE);

        return new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, scopes)
                .setAccessType("offline")
                .setApprovalPrompt("force")
                .build();
    }

    // --- Métodos que SIEMPRE usarán la cuenta de la API (Edissonavil) ---

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

    /**
     * Descarga un archivo de Google Drive usando las credenciales del usuario de la API (Edissonavil).
     *
     * @param fileId El ID del archivo en Google Drive.
     * @return Un InputStream del contenido del archivo.
     * @throws IOException Si ocurre un error al descargar el archivo.
     */
    public InputStream downloadFile(String fileId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales.");
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        this.apiDriveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        log.info("Archivo descargado de Google Drive (vía API user): ID={}", fileId);
        return new java.io.ByteArrayInputStream(outputStream.toByteArray());
    }

    /**
     * Lista archivos de Google Drive usando las credenciales del usuario de la API (Edissonavil).
     *
     * @param query Una consulta de búsqueda (ej. "name contains 'documento'").
     * @param folderId El ID de la carpeta para listar, o null para la carpeta por defecto.
     * @return Una lista de objetos File de Google Drive.
     * @throws IOException Si ocurre un error al listar los archivos.
     */
    public List<File> listFiles(String query, String folderId) throws IOException {
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

    /**
     * Elimina un archivo de Google Drive usando las credenciales del usuario de la API (Edissonavil).
     *
     * @param fileId El ID del archivo a eliminar.
     * @throws IOException Si ocurre un error.
     */
    public void deleteFile(String fileId) throws IOException {
        if (this.apiDriveService == null) {
            throw new IllegalStateException("Google Drive Service para el usuario de la API no está inicializado. Faltan credenciales.");
        }
        this.apiDriveService.files().delete(fileId).execute();
        log.info("Archivo eliminado de Google Drive (vía API user): ID={}", fileId);
    }
}