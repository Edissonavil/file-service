package com.aec.FileSrv.config;

import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity; // Importar WebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer; // Importar WebSecurityCustomizer
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;



@Configuration
@EnableMethodSecurity // Asegúrate de que esta anotación esté presente si usas seguridad a nivel de método
public class SecurityConfig {

  @Value("${jwt.secret}")
  private String jwtSecret;

  @Bean
  public JwtDecoder jwtDecoder() {
    byte[] keyBytes = Base64.getDecoder().decode(jwtSecret);
    SecretKey key   = new SecretKeySpec(keyBytes, "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).build();
  }

  /** Convierte el claim `role` en GrantedAuthority sin prefijo */
  private JwtAuthenticationConverter jwtAuthConverter() {
    JwtGrantedAuthoritiesConverter ga = new JwtGrantedAuthoritiesConverter();
    ga.setAuthorityPrefix("");          // no “SCOPE_”
    ga.setAuthoritiesClaimName("role"); // tu claim

    JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
    conv.setJwtGrantedAuthoritiesConverter(ga);
    return conv;
  }


  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .csrf(csrf -> csrf.disable()) // si ya usas CSRF tokens, ajusta esto
        .authorizeHttpRequests(auth -> auth
            // ⬇⬇ las fotos ahora son públicas
            .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()

            // ⬇⬇ PERMITE EL ACCESO A LA RUTA DE ERROR
            .requestMatchers("/error").permitAll() // <-- ¡Añade esta línea!

            // otras reglas que ya tenías
            .requestMatchers(HttpMethod.POST, "/api/files/public").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/files/secure")
            .hasAuthority("ROL_COLABORADOR")
            .requestMatchers(HttpMethod.POST, "/api/files/receipts/**")
            .hasAuthority("ROL_CLIENTE") 
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt
                .decoder(jwtDecoder())
                .jwtAuthenticationConverter(jwtAuthConverter())
            )
        );
    return http.build();
  }

}
