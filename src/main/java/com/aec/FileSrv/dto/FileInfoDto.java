package com.aec.FileSrv.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileInfoDto {
    private Long id;
    private String filename;
    private String originalName;
    private String fileType;
    private Long size;
    private String uploader;
    private String downloadUri;
}
