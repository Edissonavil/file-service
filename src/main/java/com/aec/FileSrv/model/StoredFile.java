package com.aec.FileSrv.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "stored_files")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoredFile {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String filename;       // nombre en disco

    @Column(nullable = false)
    private String originalName;   // nombre original

    @Column(nullable = false)
    private String fileType;       // MIME type

    @Column(nullable = false)
    private Long size;             // bytes

    @Column(nullable = false)
    private Instant uploadedAt;

    @Column(nullable = false)
    private String uploader;  
         // nombreUsuario del colaborador
    @Column(name = "product_id", nullable = true) // Cambiado a true
    private Long productId;

    @Column(name = "order_id", nullable = true) // Cambiado a true
    private Long orderId;
}
