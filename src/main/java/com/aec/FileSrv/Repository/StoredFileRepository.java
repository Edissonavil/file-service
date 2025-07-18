package com.aec.FileSrv.Repository;

import com.aec.FileSrv.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    Optional<StoredFile> findByFilename(String filename);
    List<StoredFile> findByProductId(Long productId); // <-- Útil para obtener todos los archivos de un producto

}
