package com.aec.FileSrv.controller;

import com.aec.FileSrv.service.GoogleOAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/files/oauth2")
@RequiredArgsConstructor
public class OAuth2Controller {

    private final GoogleOAuthService oauth;

    @GetMapping("/url")
    public ResponseEntity<String> authUrl() throws IOException {
        return ResponseEntity.ok(oauth.buildAuthorizationUrl());
    }

    @GetMapping("/callback")
    public ResponseEntity<String> callback(@RequestParam("code") String code) throws IOException {
        oauth.exchangeCodeAndStoreTokens(code);
        return ResponseEntity.ok("Tokens almacenados correctamente. Ya puedes subir archivos a Drive.");
    }

    @PostMapping("/refresh")
    public ResponseEntity<String> refresh() throws IOException {
        String access = oauth.ensureValidAccessToken();
        return ResponseEntity.ok("Nuevo access token: " + access.substring(0, Math.min(12, access.length())) + "...");
    }
}