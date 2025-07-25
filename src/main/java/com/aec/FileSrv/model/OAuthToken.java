package com.aec.FileSrv.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "oauth_tokens")
public class OAuthToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Un identificador lógico. Usaremos "google-drive" como único registro */
    @Column(nullable = false, unique = true)
    private String providerKey;

    @Column(length = 4096)
    private String accessToken;

    @Column(length = 4096)
    private String refreshToken;

    private Instant expiresAt; // instante en que expira el access token

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getProviderKey() { return providerKey; }
    public void setProviderKey(String providerKey) { this.providerKey = providerKey; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}