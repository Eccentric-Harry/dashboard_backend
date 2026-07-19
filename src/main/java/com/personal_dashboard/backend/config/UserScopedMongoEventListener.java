package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.UserOwnedDocument;
import com.personal_dashboard.backend.security.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserScopedMongoEventListener extends AbstractMongoEventListener<Object> {

    @Override
    public void onBeforeConvert(BeforeConvertEvent<Object> event) {
        if (!(event.getSource() instanceof UserOwnedDocument source)) {
            return;
        }

        String currentUserId = UserContext.getUserId().orElse(null);
        String documentUserId = source.getUserId();

        if (documentUserId == null || documentUserId.isBlank()) {
            if (currentUserId == null || currentUserId.isBlank()) {
                log.error("Blocked save of {} — no userId on document and no authenticated user bound to thread",
                        source.getClass().getSimpleName());
                throw new IllegalStateException("Cannot save user-owned document without an authenticated user");
            }
            log.debug("Auto-assigning userId={} to new {}", currentUserId, source.getClass().getSimpleName());
            source.setUserId(currentUserId);
            return;
        }

        if (currentUserId != null && !documentUserId.equals(currentUserId)) {
            log.error("Blocked cross-user save attempt: {} owned by userId={} but current thread userId={}",
                    source.getClass().getSimpleName(), documentUserId, currentUserId);
            throw new IllegalStateException("Cannot save a document owned by another user");
        }
    }
}
