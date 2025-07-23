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
import org.springframework.security.config.annotation.web.builders.WebSecurity; // Importar WebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer; // Importar WebSecurityCustomizer
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
                .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                
                // 🔓 UPLOADS PÚBLICOS
                .requestMatchers(HttpMethod.POST, "/api/files/public/**").permitAll()
                
                // 🔒 UPLOADS SEGUROS
                .requestMatchers(HttpMethod.POST, "/api/files/secure/**")
                    .hasAnyAuthority("ROL_CLIENTE", "ROL_COLABORADOR")
                .requestMatchers(HttpMethod.POST, "/api/files/receipts/**")
                    .hasAuthority("ROL_CLIENTE")
                
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
            
            // Si es una petición GET a archivos, no requiere auth
            if (requestPath.startsWith("/api/files/") && "GET".equals(method)) {
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
            "https://aecf-production.up.railway.app"
        ));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}