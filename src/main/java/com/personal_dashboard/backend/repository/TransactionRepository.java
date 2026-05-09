package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Transaction;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TransactionRepository extends MongoRepository<Transaction, String> {

    List<Transaction> findByCategory(String category);

    List<Transaction> findByType(String type);

    List<Transaction> findByDateBetween(Instant startDate, Instant endDate);

    List<Transaction> findByTypeAndDateBetween(String type, Instant startDate, Instant endDate);

    List<Transaction> findByCategoryAndDateBetween(String category, Instant startDate, Instant endDate);
}
