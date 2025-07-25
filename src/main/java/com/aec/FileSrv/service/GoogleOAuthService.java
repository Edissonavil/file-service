package com.aec.FileSrv.service;

import com.aec.FileSrv.config.OAuthProperties;
import com.aec.FileSrv.model.OAuthToken;
import com.aec.FileSrv.Repository.OAuthTokenRepository;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeRequestUrl;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.DriveScopes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Collections;

@Service
public class GoogleOAuthService {
    private static final String PROVIDER_KEY = "google-drive";

    private final OAuthProperties props;
    private final OAuthTokenRepository repo;
    private final NetHttpTransport httpTransport;
    private final JacksonFactory jsonFactory = JacksonFactory.getDefaultInstance();

    public GoogleOAuthService(OAuthProperties props, OAuthTokenRepository repo) throws GeneralSecurityException, IOException {
        this.props = props;
        this.repo = repo;
        this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    }

    private GoogleClientSecrets clientSecrets() throws IOException {
        String json = "{" +
            "\"web\":{" +
            "\"client_id\":\"" + props.getClientId() + "\"," +
            "\"client_secret\":\"" + props.getClientSecret() + "\"," +
            "\"redirect_uris\":[\"" + props.getRedirectUri() + "\"]," +
            "\"auth_uri\":\"https://accounts.google.com/o/oauth2/auth\"," +
            "\"token_uri\":\"https://oauth2.googleapis.com/token\"}}";
        try (InputStreamReader reader = new InputStreamReader(new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)))) {
            return GoogleClientSecrets.load(jsonFactory, reader);
        }
    }

    private GoogleAuthorizationCodeFlow buildFlow() throws IOException {
        return new GoogleAuthorizationCodeFlow.Builder(
                httpTransport, jsonFactory, clientSecrets(), Collections.singleton(DriveScopes.DRIVE))
            .setAccessType("offline")
            .setApprovalPrompt("force")
            .build();
    }

    public String buildAuthorizationUrl() throws IOException {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        GoogleAuthorizationCodeRequestUrl url = flow.newAuthorizationUrl()
            .setRedirectUri(props.getRedirectUri())
            .setAccessType("offline")
            .setApprovalPrompt("force");
        return url.build();
    }

    @Transactional
    public void exchangeCodeAndStoreTokens(String code) throws IOException {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        GoogleTokenResponse tokenResponse = flow.newTokenRequest(code)
            .setRedirectUri(props.getRedirectUri())
            .execute();

        String accessToken = tokenResponse.getAccessToken();
        String refreshToken = tokenResponse.getRefreshToken();
        Long   expiresInSec = tokenResponse.getExpiresInSeconds();
        Instant expiresAt = (expiresInSec != null)
            ? Instant.now().plusSeconds(expiresInSec)
            : Instant.now().plusSeconds(3300);

        OAuthToken tok = repo.findByProviderKey(PROVIDER_KEY).orElseGet(OAuthToken::new);
        tok.setProviderKey(PROVIDER_KEY);
        tok.setAccessToken(accessToken);
        if (refreshToken != null && !refreshToken.isBlank()) {
            tok.setRefreshToken(refreshToken);
        }
        tok.setExpiresAt(expiresAt);
        repo.save(tok);
    }

    @Transactional
    public String ensureValidAccessToken() throws IOException {
        OAuthToken tok = repo.findByProviderKey(PROVIDER_KEY)
            .orElseThrow(() -> new IllegalStateException("No hay tokens almacenados. Realiza el flujo OAuth2 primero."));

        if (tok.getExpiresAt() != null && tok.getExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return tok.getAccessToken();
        }
        if (tok.getRefreshToken() == null || tok.getRefreshToken().isBlank()) {
            throw new IllegalStateException("No hay refresh token. Repite el consentimiento OAuth.");
        }
        GoogleTokenResponse refreshed = new GoogleRefreshTokenRequest(
                httpTransport, jsonFactory,
                tok.getRefreshToken(), props.getClientId(), props.getClientSecret())
            .execute();

        tok.setAccessToken(refreshed.getAccessToken());
        Long exp = refreshed.getExpiresInSeconds();
        tok.setExpiresAt(exp != null ? Instant.now().plusSeconds(exp) : Instant.now().plusSeconds(3300));
        repo.save(tok);
        return tok.getAccessToken();
    }
}