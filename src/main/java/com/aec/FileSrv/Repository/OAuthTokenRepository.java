package com.aec.FileSrv.Repository;

import com.aec.FileSrv.model.OAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OAuthTokenRepository extends JpaRepository<OAuthToken, Long> {
    Optional<OAuthToken> findByProviderKey(String providerKey);
}