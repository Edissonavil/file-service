package com.aec.FileSrv.service;

import com.aec.FileSrv.model.UserGoogleDriveToken;
import com.aec.FileSrv.Repository.UserGoogleDriveTokenRepository;
import com.google.api.client.auth.oauth2.BearerToken; // NECESARIO
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

// Importar para el refresh de token más moderno
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import com.google.api.client.json.gson.GsonFactory; // Usar GsonFactory o JacksonFactory si ya lo tienes

@Service
@Slf4j
public class GoogleDriveService {

    @Value("${google.drive.client-id}")
    private String clientId;

    @Value("${google.drive.client-secret}")
    private String clientSecret;

    @Value("${google.drive.redirect-uri}")
    private String redirectUri;

    @Value("${google.drive.folder-id:}") // Carpeta por defecto, si está configurada
    private String defaultFolderId;

    // Puedes usar GsonFactory, o si ya tienes Jackson para otras cosas, usar JacksonFactory
    // private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance(); // Cambio sugerido: GsonFactory es común en ejemplos de Google

    private HttpTransport httpTransport;

    private final UserGoogleDriveTokenRepository tokenRepository;

    public GoogleDriveService(UserGoogleDriveTokenRepository tokenRepository) {
        this.tokenRepository = tokenRepository;
        try {
            this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error al inicializar HttpTransport para Google Drive: {}", e.getMessage());
            throw new RuntimeException("No se pudo inicializar Google Drive Service", e);
        }
    }


    /**
     * Paso 3.1: Generar la URL de autorización
     *
     * @param userId El ID del usuario que se va a autenticar (ej. el subject del JWT).
     * @return La URL a la que el frontend debe redirigir al usuario.
     */
    public String getAuthorizationUrl(String userId) {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        String state = userId; 
        return flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .setState(state)
                .setAccessType("offline") // Para obtener un refresh token
                .build();
    }

    /**
     * Paso 3.2: Manejar el Callback de Google
     *
     * @param code El código de autorización recibido de Google.
     * @param state El estado recibido de Google (debería ser el userId).
     * @return El token de acceso (Access Token) o null si falla.
     */
    @Transactional
    public String exchangeCodeForTokens(String code, String state) throws IOException {
        String userId = state; // Recuperamos el userId del estado
        GoogleAuthorizationCodeFlow flow = buildFlow();
        TokenResponse tokenResponse = flow.newTokenRequest(code)
                .setRedirectUri(redirectUri)
                .execute();

        // Construir Credential a partir de la respuesta del token
        // Usamos BearerToken.authorizationHeaderAccessMethod() y setFromTokenResponse
        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setFromTokenResponse(tokenResponse);

        // Guardar o actualizar los tokens en la base de datos
        UserGoogleDriveToken userToken = tokenRepository.findByUserId(userId)
                .orElse(new UserGoogleDriveToken());

        userToken.setUserId(userId);
        userToken.setAccessToken(credential.getAccessToken());
        userToken.setRefreshToken(credential.getRefreshToken()); // Puede ser null si no se obtuvo
        userToken.setTokenCreationTime(Instant.now());
        userToken.setExpiresInSeconds(credential.getExpiresInSeconds());
        // setScope: Credential.getGrantedScopes() no existe. Usamos los scopes predefinidos.
        userToken.setScope(String.join(" ", Collections.singletonList(DriveScopes.DRIVE_FILE))); 

        tokenRepository.save(userToken);
        log.info("Tokens de Google Drive guardados para el usuario: {}", userId);
        return credential.getAccessToken();
    }

    /**
     * Paso 3.3: Refrescar el Access Token y obtener Credential
     *
     * @param userId El ID del usuario.
     * @return Credential actualizada.
     * @throws IOException Si falla el refresco o no hay tokens.
     */
    @Transactional
    public Credential getCredential(String userId) throws IOException {
        Optional<UserGoogleDriveToken> userTokenOpt = tokenRepository.findByUserId(userId);
        if (userTokenOpt.isEmpty()) {
            throw new IOException("No hay tokens de Google Drive para el usuario: " + userId);
        }

        UserGoogleDriveToken userToken = userTokenOpt.get();

        // Calcular si el token actual está a punto de expirar o ya expiró
        long expiresInMillis = userToken.getTokenCreationTime().toEpochMilli() + userToken.getExpiresInSeconds() * 1000;
        
// Refrescar si el token expira en menos de 60 segundos O si ya expiró Y hay un refresh token
if (System.currentTimeMillis() >= expiresInMillis - (60 * 1000) && userToken.getRefreshToken() != null) {
    log.info("Intentando refrescar token de acceso para el usuario: {}", userId);
    try {
        // Usar com.google.auth.oauth2.UserCredentials para el refresh
        UserCredentials creds = UserCredentials.newBuilder() // <<-- CAMBIO AQUÍ: Usar UserCredentials.newBuilder()
            .setClientId(clientId)
            .setClientSecret(clientSecret)
            .setRefreshToken(userToken.getRefreshToken())
            .build();

        creds.refresh(); // Esto refrescará el token

                // Actualizar los tokens en la base de datos con los nuevos valores
                userToken.setAccessToken(creds.getAccessToken().getTokenValue());
                userToken.setTokenCreationTime(Instant.ofEpochMilli(creds.getAccessToken().getExpirationTime().getTime()));
                // Puedes calcular expiresInSeconds a partir de la nueva expiración
                userToken.setExpiresInSeconds((creds.getAccessToken().getExpirationTime().getTime() - Instant.now().toEpochMilli()) / 1000);
                tokenRepository.save(userToken);
                log.info("Token de acceso refrescado y guardado para el usuario: {}", userId);

                // Construir una nueva Credential de com.google.api.client.auth.oauth2 con los tokens actualizados
                return new Credential(BearerToken.authorizationHeaderAccessMethod())
                    .setAccessToken(userToken.getAccessToken())
                    .setRefreshToken(userToken.getRefreshToken())
                    .setExpirationTimeMilliseconds(userToken.getTokenCreationTime().toEpochMilli() + userToken.getExpiresInSeconds() * 1000);

            } catch (Exception e) { // Captura Exception ya que refresh() puede lanzar varias (ej. si el refresh token es inválido)
                log.warn("No se pudo refrescar el token para el usuario: {}. Error: {}", userId, e.getMessage());
                // Si el refresh falla, el token existente no es válido, se necesita reautenticación
                throw new IOException("No se pudo refrescar el token de acceso. Reautenticación necesaria.", e);
            }
        } else if (System.currentTimeMillis() >= expiresInMillis && userToken.getRefreshToken() == null) {
            // Si el token expiró y NO hay refresh token, se necesita reautenticación
            log.warn("Token expirado y sin refresh token para el usuario: {}. Necesita reautenticarse.", userId);
            throw new IOException("Token expirado y sin refresh token. Reautenticación necesaria.");
        } else if (System.currentTimeMillis() >= expiresInMillis && userToken.getRefreshToken() != null && userToken.getAccessToken() == null) {
            // Caso borde: token de acceso es nulo pero hay refresh. Intentar refrescar (ya se manejó arriba)
            // Si llega aquí, significa que el refresh de arriba falló o no se intentó porque no estaba "a punto de expirar"
            // pero ya expiró. Aquí forzar reautenticación o un refresh explícito.
            log.warn("Token de acceso ausente pero con refresh token para el usuario: {}. Forzando reautenticación.", userId);
            throw new IOException("Token de acceso ausente. Reautenticación necesaria.");
        }

        // Si el token aún es válido y no necesita refresh (o no tiene refresh token pero aún es válido)
        // Construir Credential con los tokens actuales
        return new Credential(BearerToken.authorizationHeaderAccessMethod())
            .setAccessToken(userToken.getAccessToken())
            .setRefreshToken(userToken.getRefreshToken())
            .setExpirationTimeMilliseconds(userToken.getTokenCreationTime().toEpochMilli() + userToken.getExpiresInSeconds() * 1000);
    }

    private GoogleAuthorizationCodeFlow buildFlow() {
        // La creación de GoogleClientSecrets.Builder se ha simplificado en versiones recientes
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
        details.setClientId(clientId);
        details.setClientSecret(clientSecret);
        clientSecrets.setWeb(details);
        
        List<String> scopes = Collections.singletonList(DriveScopes.DRIVE_FILE); // O DriveScopes.DRIVE

        return new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, JSON_FACTORY, clientSecrets, scopes)
                .setAccessType("offline") // Importante para obtener un refresh token
                .setApprovalPrompt("force") // Fuerza la pantalla de consentimiento cada vez (útil en desarrollo)
                .build();
    }


    /**
     * Obtiene una instancia de Drive con las credenciales del usuario.
     * @param userId El ID del usuario.
     * @return Una instancia de Drive.
     * @throws IOException Si no se pueden obtener las credenciales.
     */
    private Drive getDriveService(String userId) throws IOException {
        Credential credential = getCredential(userId);
        return new Drive.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName("AEC File Service") // Nombre de tu aplicación
                .build();
    }

    /**
     * 4.1: Subir un archivo a Google Drive
     *
     * @param userId El ID del usuario que sube el archivo.
     * @param fileContent El InputStream del archivo.
     * @param mimeType El tipo MIME del archivo (ej. "image/jpeg", "application/pdf").
     * @param fileName El nombre del archivo en Google Drive.
     * @param parentFolderId El ID de la carpeta donde subir el archivo. Si es null o vacío, se sube a la raíz.
     * @return El ID del archivo creado en Google Drive.
     * @throws IOException Si ocurre un error al subir el archivo.
     */
    public String uploadFile(String userId, InputStream fileContent, String mimeType, String fileName, String parentFolderId) throws IOException {
        Drive driveService = getDriveService(userId);

        File fileMetadata = new File();
        fileMetadata.setName(fileName);

        if (parentFolderId != null && !parentFolderId.isEmpty()) {
            fileMetadata.setParents(Collections.singletonList(parentFolderId));
        } else if (defaultFolderId != null && !defaultFolderId.isEmpty()) {
            fileMetadata.setParents(Collections.singletonList(defaultFolderId));
        }

        InputStreamContent mediaContent = new InputStreamContent(mimeType, fileContent);
        File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                .setFields("id, name, mimeType")
                .execute();

        log.info("Archivo subido a Google Drive: ID={}, Nombre={}", uploadedFile.getId(), uploadedFile.getName());
        return uploadedFile.getId();
    }

    /**
     * 4.2: Descargar un archivo de Google Drive
     *
     * @param userId El ID del usuario propietario del archivo.
     * @param fileId El ID del archivo en Google Drive.
     * @return Un InputStream del contenido del archivo.
     * @throws IOException Si ocurre un error al descargar el archivo.
     */
    public InputStream downloadFile(String userId, String fileId) throws IOException {
        Drive driveService = getDriveService(userId);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        driveService.files().get(fileId).executeMediaAndDownloadTo(outputStream);
        log.info("Archivo descargado de Google Drive: ID={}", fileId);
        return new java.io.ByteArrayInputStream(outputStream.toByteArray());
    }

    /**
     * 4.3: Listar archivos en Google Drive
     *
     * @param userId El ID del usuario.
     * @param query Una consulta de búsqueda (ej. "name contains 'documento'").
     * @param folderId El ID de la carpeta para listar, o null para la raíz.
     * @return Una lista de objetos File de Google Drive.
     * @throws IOException Si ocurre un error al listar los archivos.
     */
    public List<File> listFiles(String userId, String query, String folderId) throws IOException {
        Drive driveService = getDriveService(userId);
        Drive.Files.List request = driveService.files().list()
                .setFields("nextPageToken, files(id, name, mimeType, size, createdTime, modifiedTime, parents)");

        if (folderId != null && !folderId.isEmpty()) {
            // Buscar dentro de una carpeta específica
            request.setQ("'" + folderId + "' in parents and trashed = false" + (query != null && !query.isEmpty() ? " and " + query : ""));
        } else if (query != null && !query.isEmpty()) {
            // Buscar en todo el Drive si no hay folderId
            request.setQ(query + " and trashed = false");
        } else {
            // Listar archivos en la raíz si no hay query ni folderId
            request.setQ("'root' in parents and trashed = false");
        }


        FileList files = request.execute();
        log.info("Archivos listados para el usuario {}: {} resultados", userId, files.getFiles().size());
        return files.getFiles();
    }

    /**
     * Opcional: Eliminar un archivo de Google Drive
     * @param userId El ID del usuario.
     * @param fileId El ID del archivo a eliminar.
     * @throws IOException Si ocurre un error.
     */
    public void deleteFile(String userId, String fileId) throws IOException {
        Drive driveService = getDriveService(userId);
        driveService.files().delete(fileId).execute();
        log.info("Archivo eliminado de Google Drive: ID={}", fileId);
    }
}