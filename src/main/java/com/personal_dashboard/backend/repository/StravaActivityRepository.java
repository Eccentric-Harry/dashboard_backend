package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.StravaActivity;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StravaActivityRepository extends MongoRepository<StravaActivity, String> {

    List<StravaActivity> findAllByOrderByDateDesc();

    List<StravaActivity> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<StravaActivity> findBySportType(String sportType);

    Optional<StravaActivity> findByStravaEmbedId(String stravaEmbedId);
}
