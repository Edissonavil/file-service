package com.aec.FileSrv.drive;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DriveFile {
    private String id;
    private String name;
    private String mimeType;
    private Long size;
}
