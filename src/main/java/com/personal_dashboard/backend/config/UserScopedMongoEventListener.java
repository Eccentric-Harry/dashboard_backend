package com.personal_dashboard.backend.config;

import com.personal_dashboard.backend.model.UserOwnedDocument;
import com.personal_dashboard.backend.security.UserContext;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeConvertEvent;
import org.springframework.stereotype.Component;

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
                throw new IllegalStateException("Cannot save user-owned document without an authenticated user");
            }
            source.setUserId(currentUserId);
            return;
        }

        if (currentUserId != null && !documentUserId.equals(currentUserId)) {
            throw new IllegalStateException("Cannot save a document owned by another user");
        }
    }
}
