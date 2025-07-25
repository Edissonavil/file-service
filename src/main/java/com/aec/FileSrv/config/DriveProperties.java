package com.aec.FileSrv.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "gdrive")
public class DriveProperties {
    /** Carpeta para archivos de PRODUCTOS */
    private String folderProductId;
    /** Carpeta para archivos de COMPROBANTES */
    private String folderReceiptId;

    public String getFolderProductId() { return folderProductId; }
    public void setFolderProductId(String folderProductId) { this.folderProductId = folderProductId; }
    public String getFolderReceiptId() { return folderReceiptId; }
    public void setFolderReceiptId(String folderReceiptId) { this.folderReceiptId = folderReceiptId; }
}