package com.aec.FileSrv.config;

import com.aec.FileSrv.service.GoogleOAuthService;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.drive.Drive;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;

@Configuration
public class GoogleDriveConfig {

    @Bean
    public Drive drive(GoogleOAuthService oauth) throws GeneralSecurityException, IOException {
        NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        JacksonFactory jsonFactory = JacksonFactory.getDefaultInstance();

        HttpRequestInitializer initializer = request -> {
            try {
                String accessToken = oauth.ensureValidAccessToken();
                request.getHeaders().setAuthorization("Bearer " + accessToken);
            } catch (IOException e) {
                throw new RuntimeException("No se pudo obtener/renovar access token de Google", e);
            }
        };

        return new Drive.Builder(transport, jsonFactory, initializer)
            .setApplicationName("AEC-FileService")
            .build();
    }
}