package com.personal_dashboard.backend.repository;

import com.personal_dashboard.backend.model.WishlistItem;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WishlistItemRepository extends MongoRepository<WishlistItem, String> {

    List<WishlistItem> findByUserId(String userId);
}
