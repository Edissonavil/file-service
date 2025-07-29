package com.aec.FileSrv.service;

import com.aec.FileSrv.config.DriveProperties;
import com.aec.FileSrv.controller.FileController;
import com.aec.FileSrv.drive.DriveFile;
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
import java.util.ArrayList;
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
        String q = "'" + (isProduct ? props.getFolderProductId() : props.getFolderReceiptId())
                + "' in parents and trashed=false";
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

    public String uploadFileToFolder(MultipartFile multipart, String folderPath) throws IOException {
        String folderId = getOrCreateFolder(folderPath);

        String filename = multipart.getOriginalFilename() != null ? multipart.getOriginalFilename() : "file";
        String mime = multipart.getContentType() != null ? multipart.getContentType() : "application/octet-stream";

        // 1. Buscar si el archivo ya existe en la carpeta de Drive
        String existingFileId = findFileInFolder(folderId, filename);

        File metadata = new File()
                .setName(filename)
                .setParents(List.of(folderId));

        try (InputStream in = multipart.getInputStream()) {
            InputStreamContent mediaContent = new InputStreamContent(mime, in);
            mediaContent.setLength(multipart.getSize());

            File uploaded;
            if (existingFileId != null) {
                // 2. Si existe, actualizar el archivo existente
                uploaded = drive.files()
                        .update(existingFileId, metadata, mediaContent)
                        .setFields("id")
                        .execute();
                log.info("Drive.update OK -> id={}, name={}", uploaded.getId(), uploaded.getName());
            } else {
                // 3. Si no existe, crear un nuevo archivo
                uploaded = drive.files()
                        .create(metadata, mediaContent)
                        .setFields("id")
                        .execute();
                log.info("Drive.create OK -> id={}, name={}", uploaded.getId(), uploaded.getName());
            }

            return uploaded.getId();
        }
    }

    // Nuevo método para buscar un archivo por nombre en una carpeta específica
    private String findFileInFolder(String folderId, String filename) throws IOException {
        String q = "'" + folderId + "' in parents and name='" + filename.replace("'", "\\'") + "' and trashed=false";
        FileList result = drive.files().list()
                .setQ(q)
                .setFields("files(id)")
                .setPageSize(1)
                .execute();
        if (result.getFiles() != null && !result.getFiles().isEmpty()) {
            return result.getFiles().get(0).getId();
        }
        return null;
    }

    public String getOrCreateFolder(String path) throws IOException {
        String[] parts = path.split("/");
        String parentId = "root";

        for (String part : parts) {
            String folderId = findChildFolderId(parentId, part);
            if (folderId == null) {
                folderId = createFolder(parentId, part);
            }
            parentId = folderId;
        }
        return parentId;
    }

    private String findChildFolderId(String parentId, String name) throws IOException {
        String q = "'" + parentId + "' in parents and mimeType='application/vnd.google-apps.folder' " +
                "and name='" + name.replace("'", "\\'") + "' and trashed=false";
        FileList result = drive.files().list()
                .setQ(q)
                .setFields("files(id, name)")
                .execute();
        if (result.getFiles() == null || result.getFiles().isEmpty())
            return null;
        return result.getFiles().get(0).getId();
    }

    private String createFolder(String parentId, String name) throws IOException {
        File metadata = new File()
                .setName(name)
                .setMimeType("application/vnd.google-apps.folder")
                .setParents(List.of(parentId));
        File folder = drive.files().create(metadata)
                .setFields("id")
                .execute();
        return folder.getId();
    }

    /** Lista archivos regulares dentro de un folder */
    public List<DriveFile> listFilesInFolder(String folderId) throws IOException {
        String q = "'" + folderId + "' in parents and trashed=false " +
                "and mimeType != 'application/vnd.google-apps.folder'";

        List<DriveFile> out = new ArrayList<>();
        String pageToken = null;
        do {
            FileList result = drive.files().list()
                    .setQ(q)
                    .setFields("nextPageToken, files(id, name, mimeType, size)")
                    .setPageToken(pageToken)
                    .execute();

            if (result.getFiles() != null) {
                for (File f : result.getFiles()) {
                    out.add(new DriveFile(
                            f.getId(),
                            f.getName(),
                            f.getMimeType(),
                            f.getSize()));
                }
            }
            pageToken = result.getNextPageToken();
        } while (pageToken != null);

        return out;
    }

    /**
     * Sube directamente al folder cuyo ID ya tienes, sin volver a crear carpetas
     */
    public String uploadFileToFolderById(MultipartFile multipart, String folderId) throws IOException {
        String filename = multipart.getOriginalFilename() != null
                ? multipart.getOriginalFilename()
                : "file";
        String mime = multipart.getContentType() != null
                ? multipart.getContentType()
                : "application/octet-stream";

        File metadata = new File()
                .setName(filename)
                .setParents(List.of(folderId)); // aquí usamos folderId directamente

        try (InputStream in = multipart.getInputStream()) {
            InputStreamContent mediaContent = new InputStreamContent(mime, in);
            mediaContent.setLength(multipart.getSize());

            File uploaded = drive.files()
                    .create(metadata, mediaContent)
                    .setFields("id")
                    .execute();

            return uploaded.getId();
        }
    }

}