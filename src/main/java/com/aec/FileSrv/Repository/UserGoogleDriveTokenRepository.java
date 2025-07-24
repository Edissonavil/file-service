package com.aec.FileSrv.Repository;

import com.aec.FileSrv.model.UserGoogleDriveToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserGoogleDriveTokenRepository extends JpaRepository<UserGoogleDriveToken, Long> {
    Optional<UserGoogleDriveToken> findByUserId(String userId);
}
