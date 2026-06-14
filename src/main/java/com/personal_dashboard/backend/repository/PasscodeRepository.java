package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.Passcode;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasscodeRepository extends MongoRepository<Passcode, String> {
}
