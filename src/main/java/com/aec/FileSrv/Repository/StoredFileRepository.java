package com.aec.FileSrv.Repository;

import com.aec.FileSrv.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findByProductId(Long productId);
    Optional<StoredFile> findByDriveFileId(String driveFileId);
    Optional<StoredFile> findByProductIdAndFilename(Long productId, String filename);
    Optional<StoredFile> findByOrderIdAndFilename(Long orderId, String filename);

}
