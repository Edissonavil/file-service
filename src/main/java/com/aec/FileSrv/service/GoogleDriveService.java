package com.aec.FileSrv.service;

import com.aec.FileSrv.config.DriveProperties;
import com.aec.FileSrv.controller.FileController;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;

import io.micrometer.common.lang.Nullable;
import lombok.RequiredArgsConstructor;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.InputStreamContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final Logger log = LoggerFactory.getLogger(FileController.class);

public String uploadFile(MultipartFile file, boolean isProduct) throws IOException {
        String parentId = isProduct ? props.getFolderProductId() : props.getFolderReceiptId();
        String name = file.getOriginalFilename();
        String ctype = file.getContentType();

        log.info("Drive.uploadFile: isProduct={}, parentId={}, name={}, contentType={}, size={}",
                isProduct, parentId, name, ctype, file.getSize());

        File metadata = new File();
        metadata.setName(name);
        metadata.setParents(Collections.singletonList(parentId));

        try (InputStream in = file.getInputStream()) {
            InputStreamContent media = new InputStreamContent(
                    ctype != null ? ctype : "application/octet-stream", in);

            File created = drive.files()
                    .create(metadata, media)
                    .setFields("id,name,mimeType,size,webViewLink,webContentLink,parents")
                    .execute();

            log.info("Drive.create OK -> id={}, name={}, mimeType={}, size={}, parents={}",
                    created.getId(), created.getName(), created.getMimeType(),
                    created.getSize(), created.getParents());
            return created.getId();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            log.error("Drive.create ERROR code={}, status={}, details={}",
                    e.getStatusCode(), e.getStatusMessage(),
                    e.getDetails() != null ? e.getDetails().toPrettyString() : "(sin details)", e);
            throw e;
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

public String getOrCreateFolder(String name, String parentId) throws IOException {
    StringBuilder q = new StringBuilder();
    q.append("mimeType = 'application/vnd.google-apps.folder' ");
    q.append("and name = '").append(name.replace("'", "\\'")).append("' ");
    q.append("and trashed = false");
    if (parentId != null) {
        q.append(" and '").append(parentId).append("' in parents");
    }

    FileList result = drive.files().list()
            .setQ(q.toString())
            .setFields("files(id,name)")
            .setPageSize(1)
            .execute();

    if (result.getFiles() != null && !result.getFiles().isEmpty()) {
        return result.getFiles().get(0).getId();
    }

    File folder = new File();
    folder.setName(name);
    folder.setMimeType("application/vnd.google-apps.folder");
    if (parentId != null) {
        folder.setParents(java.util.List.of(parentId));
    }

    File created = drive.files().create(folder)
            .setFields("id")
            .execute();

    return created.getId();
}


public String uploadFileToFolder(MultipartFile mf, String folderId) throws IOException {
    File meta = new File();
    meta.setName(mf.getOriginalFilename());
    meta.setParents(java.util.List.of(folderId));

    InputStreamContent mediaContent =
            new InputStreamContent(mf.getContentType(), mf.getInputStream());
    mediaContent.setLength(mf.getSize());

    File uploaded = drive.files()
            .create(meta, mediaContent)
            .setFields("id")
            .execute();

    return uploaded.getId();
}




}