package com.sb24.api.repo;

import com.sb24.api.domain.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);
    org.springframework.data.domain.Page<AppUser> findByRole(com.sb24.api.domain.Role role, org.springframework.data.domain.Pageable pageable);
    boolean existsByEmailOrNationalIdIndex(String email, String nationalIdIndex);
}
