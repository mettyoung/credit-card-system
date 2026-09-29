package com.mettyoung.creditcardapplication.application.internal;

import com.mettyoung.creditcardapplication.application.RequirementType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EvidenceRequirementRepository extends JpaRepository<EvidenceRequirement, UUID> {

    List<EvidenceRequirement> findByApplicationId(UUID applicationId);

    Optional<EvidenceRequirement> findByApplicationIdAndType(UUID applicationId, RequirementType type);
}
