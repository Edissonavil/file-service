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

    // Nuevo campo para almacenar el ID del archivo en Google Drive
    @Column(name = "google_drive_file_id", nullable = false, unique = true)
    private String googleDriveFileId;

    @Column(nullable = false)
    private String filename; 

    @Column(nullable = false)
    private String originalName;   // nombre original

    @Column(nullable = false)
    private String fileType;       // MIME type

    @Column(nullable = false)
    private Long size;             // bytes

    @Column(nullable = false)
    private Instant uploadedAt;

    @Column(nullable = false)
    private String uploader;  // nombreUsuario del colaborador

    @Column(name = "product_id", nullable = true)
    private Long productId;

    @Column(name = "order_id", nullable = true)
    private Long orderId;

    @Column(nullable = false, unique = true)
    private String driveFileId;
}