package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.ShoppingItem;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ShoppingItemRepository extends MongoRepository<ShoppingItem, String> {

    List<ShoppingItem> findByUserId(String userId);

    List<ShoppingItem> findByUserIdAndChecked(String userId, boolean checked);

    long countByUserId(String userId);
}
