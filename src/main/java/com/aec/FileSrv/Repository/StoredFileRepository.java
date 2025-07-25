package com.aec.FileSrv.Repository;

import com.aec.FileSrv.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findByProductId(Long productId);
    Optional<StoredFile> findByGoogleDriveFileId(String googleDriveFileId);
    Optional<StoredFile> findByDriveFileId(String driveFileId);

}
