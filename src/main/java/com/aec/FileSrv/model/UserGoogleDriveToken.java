package com.aec.FileSrv.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "user_google_drive_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserGoogleDriveToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Identificador único del usuario (ej. el 'sub' del JWT)
    @Column(nullable = false, unique = true)
    private String userId;

    @Column(nullable = false, length = 1024) // Longitud suficiente para el token
    private String accessToken;

    @Column(nullable = true, length = 1024) // Refresh token puede ser nulo si el scope no lo permite
    private String refreshToken;

    @Column(nullable = false)
    private Instant tokenCreationTime;

    @Column(nullable = false)
    private Long expiresInSeconds; // Tiempo de vida del Access Token en segundos

    @Column(nullable = false)
    private String scope; // Ámbitos concedidos
}
