package com.aec.FileSrv.config;

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

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.List;
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
                // Actuator / management
                .requestMatchers("/management/**", "/actuator/**").permitAll()

                // Público para ficheros y OAuth2
                .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/api/files/**").permitAll()
                .requestMatchers("/api/files/oauth2/**").permitAll()

                // Otros públicos
                .requestMatchers("/error").permitAll()
                .requestMatchers("/favicon.ico").permitAll()

                // Uploads
                .requestMatchers(HttpMethod.POST, "/api/files/public/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/files/secure/**")
                    .hasAnyAuthority("ROL_CLIENTE", "ROL_COLABORADOR")
                .requestMatchers(HttpMethod.POST, "/api/files/receipts/**")
                    .hasAuthority("ROL_CLIENTE")

                // Resto autenticado
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder())
                    .jwtAuthenticationConverter(jwtAuthConverter())
                )
                .authenticationEntryPoint(customAuthenticationEntryPoint())
            )
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    @Bean
    public AuthenticationEntryPoint customAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            String path = request.getRequestURI();
            String method = request.getMethod();

            // Estas rutas ya están permitAll, pero si por alguna razón pasan por aquí, aseguramos 401 solo en lo privado.
            boolean isPublic =
                    path.startsWith("/management/") ||
                    path.startsWith("/actuator/")  ||
                    path.startsWith("/api/files/oauth2/") ||
                    (path.startsWith("/api/files/") && "GET".equals(method)) ||
                    (path.startsWith("/api/files/public/") && "POST".equals(method)) ||
                    path.equals("/error") ||
                    path.equals("/favicon.ico");

            if (isPublic) {
                // Si llega aquí y era pública, devolvemos 200 para no romper UX
                response.setStatus(HttpStatus.OK.value());
                return;
            }

            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"No autorizado\",\"path\":\"" + path + "\"}");
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(
            "https://gateway-production-129e.up.railway.app",
            "https://aecf-production.up.railway.app",
            "https://file-service-production-31f3.up.railway.app",
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
