package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.ApplicationStatus;
import com.mettyoung.creditcardapplication.application.CardProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ApplicationRepository extends JpaRepository<Application, UUID>, ApplicationQueries {

    Optional<Application> findByIdAndUserId(UUID id, String userId);

    Optional<Application> findByUserIdAndCardProductCodeAndStatus(String userId, CardProduct cardProductCode, ApplicationStatus status);
}
