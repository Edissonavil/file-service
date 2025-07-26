package com.aec.FileSrv.service;

import com.aec.FileSrv.config.DriveProperties;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GoogleDriveService {
    private final Drive drive;
    private final DriveProperties props;

    public String uploadFile(MultipartFile file, boolean isProduct) throws IOException {
        File metadata = new File();
        metadata.setName(file.getOriginalFilename());
        metadata.setParents(Collections.singletonList(isProduct ? props.getFolderProductId() : props.getFolderReceiptId()));

        try (InputStream in = file.getInputStream()) {
            InputStreamContent media = new InputStreamContent(file.getContentType(), in);
            File created = drive.files()
                .create(metadata, media)
                .setFields("id, name, webViewLink, webContentLink")
                .execute();
            return created.getId();
        }
    }

    public InputStream downloadFile(String fileId) throws IOException {
        return drive.files().get(fileId).executeMediaAsInputStream();
    }

    public List<File> listFiles(boolean isProduct, int pageSize) throws IOException {
        String q = "'" + (isProduct ? props.getFolderProductId() : props.getFolderReceiptId()) + "' in parents and trashed=false";
        return drive.files().list()
            .setQ(q)
            .setPageSize(pageSize)
            .setFields("files(id, name, mimeType, size, webViewLink)")
            .execute()
            .getFiles();
    }
    
    public void deleteFile(String fileId) throws IOException {
    drive.files().delete(fileId).execute();
}


}