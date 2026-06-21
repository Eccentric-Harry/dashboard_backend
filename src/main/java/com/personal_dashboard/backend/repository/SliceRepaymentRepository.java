package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.SliceRepayment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SliceRepaymentRepository extends MongoRepository<SliceRepayment, String> {
    List<SliceRepayment> findByUserId(String userId);
}
