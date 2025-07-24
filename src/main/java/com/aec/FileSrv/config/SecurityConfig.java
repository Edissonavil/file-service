package com.aec.FileSrv.config;

import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Bean
    public JwtDecoder jwtDecoder() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
        SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtGrantedAuthoritiesConverter ga = new JwtGrantedAuthoritiesConverter();
        ga.setAuthorityPrefix("");
        ga.setAuthoritiesClaimName("role");

        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter(ga);
        return conv;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // 🔓 RUTAS COMPLETAMENTE PÚBLICAS
                // Permitir acceso a archivos por su ID de Google Drive (para descarga/visualización)
                .requestMatchers(HttpMethod.GET, "/api/files/{googleDriveFileId:.+}").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/{googleDriveFileId:.+}").permitAll()
                // Permitir el callback de Google Drive (Google lo llamará sin JWT)
                .requestMatchers(HttpMethod.GET, "/api/files/google-drive/oauth2callback").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/google-drive/oauth2callback").permitAll()
                // Permitir acceso a la página de error (fallback)
                .requestMatchers("/error").permitAll()
                // Permitir acceso al endpoint de salud del actuador
                .requestMatchers("/actuator/health").permitAll()
                
                // 🔓 UPLOADS PÚBLICOS (para ProductService, etc.)
                .requestMatchers(HttpMethod.POST, "/api/files/public/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/public/**").permitAll() // Añadido OPTIONS para CORS

                // *** INICIO DEL CAMBIO CRÍTICO TEMPORAL ***
                // 🔓 Google Drive Authorize: Permitir acceso sin autenticación para iniciar el flujo OAuth
                .requestMatchers(HttpMethod.GET, "/api/files/google-drive/authorize").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/google-drive/authorize").permitAll()
                // *** FIN DEL CAMBIO CRÍTICO TEMPORAL ***
                
                // 🔒 UPLOADS SEGUROS
                .requestMatchers(HttpMethod.POST, "/api/files/secure/**")
                    .hasAnyAuthority("ROL_CLIENTE", "ROL_COLABORADOR")
                .requestMatchers(HttpMethod.POST, "/api/files/receipts/**")
                    .hasAuthority("ROL_CLIENTE")
                
                // 🔒 Google Drive List (requiere autenticación de tu API)
                .requestMatchers(HttpMethod.GET, "/api/files/google-drive/list").authenticated()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/google-drive/list").authenticated()
                // 🔒 Google Drive Download (a través de tu API, requiere auth si no se pasa uploaderId)
                // Se gestiona la autenticación del uploaderId dentro del controlador si es público
                .requestMatchers(HttpMethod.GET, "/api/files/google-drive/{googleDriveFileId}/download").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/google-drive/{googleDriveFileId}/download").permitAll()

                // 🔒 TODO LO DEMÁS
                .anyRequest().authenticated()
            )
            // ⚠️ CONFIGURACIÓN CRÍTICA: Solo aplica seguridad donde es necesaria
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthConverter())
                )
                .authenticationEntryPoint(customAuthenticationEntryPoint())
            )
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            );
        
        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            String requestPath = request.getRequestURI();
            String method = request.getMethod();
            
            // Si es una petición GET a archivos públicos o callback de Google, no requiere auth
            if (requestPath.startsWith("/api/files/") && "GET".equals(method) &&
                (requestPath.matches("/api/files/[^/]+$") || // /api/files/{googleDriveFileId}
                 requestPath.matches("/api/files/google-drive/[^/]+/download.*") || // /api/files/google-drive/{id}/download
                 requestPath.equals("/api/files/google-drive/oauth2callback") ||
                 requestPath.equals("/api/files/google-drive/authorize"))) { // <-- Añadido aquí también para el Custom Entry Point
                response.setStatus(HttpStatus.OK.value());
                return;
            }
            
            // Si es POST público, tampoco requiere auth
            if (requestPath.startsWith("/api/files/public/") && "POST".equals(method)) {
                response.setStatus(HttpStatus.OK.value());
                return;
            }
            
            // Para todo lo demás, 401
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"No autorizado\",\"path\":\"" + requestPath + "\"}");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(
            "https://gateway-production-129e.up.railway.app",
            "https://aecf-production.up.railway.app",
            "https://aecblock.com"
        ));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}