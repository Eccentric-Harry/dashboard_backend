package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Passcode;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PasscodeRepository extends MongoRepository<Passcode, String> {
    List<Passcode> findByUserId(String userId);
    List<Passcode> findByUserIdNot(String userId);
}
