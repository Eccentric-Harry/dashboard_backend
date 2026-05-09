package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.FoodEntry;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface FoodEntryRepository extends MongoRepository<FoodEntry, String> {

    List<FoodEntry> findByDate(LocalDate date);

    List<FoodEntry> findByDateBetween(LocalDate startDate, LocalDate endDate);

    List<FoodEntry> findByMealType(String mealType);

    List<FoodEntry> findByDateAndMealType(LocalDate date, String mealType);

    boolean existsByImportKey(String importKey);
}
