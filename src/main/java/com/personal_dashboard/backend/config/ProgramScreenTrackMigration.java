package com.personal_dashboard.backend.config;

import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.personal_dashboard.backend.model.Program;
import com.personal_dashboard.backend.model.ProgramLog;
import com.personal_dashboard.backend.model.ProgramReview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Retires the Lighthouse's Screen track (design/LIGHTHOUSE_90_PLAN.md). Urge surfing outlived
 * it: it used to file its logs under {@code screen}, and now files them under their own
 * {@code urge} key. So, in order:
 * <ol>
 *   <li>urges ridden out move to {@code track: "urge"}, keeping their day and note;</li>
 *   <li>every other {@code screen} log — the minutes and the two phone rules — is deleted;</li>
 *   <li>the {@code screen} entry leaves every program's tracks, its if-then plan with it;</li>
 *   <li>past reviews drop their screen-cap changes.</li>
 * </ol>
 *
 * <p>Raw collection writes, like {@link FinanceTransactionIdMigration}: there is no user context
 * at startup. Idempotent — once nothing says {@code screen}, every step matches nothing.
 * Fail-soft: a migration must never take startup down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProgramScreenTrackMigration {

    static final String SCREEN = "screen";

    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void retireScreenTrack() {
        try {
            var logs = mongoTemplate.getCollection(mongoTemplate.getCollectionName(ProgramLog.class));
            long urges = logs.updateMany(
                    Filters.and(Filters.eq("track", SCREEN), Filters.eq("urge", true)),
                    Updates.combine(Updates.set("track", "urge"), Updates.unset("value"), Updates.unset("level"),
                            Updates.unset("morningRule"), Updates.unset("nightRule"))).getModifiedCount();
            long deleted = logs.deleteMany(Filters.eq("track", SCREEN)).getDeletedCount();

            long programs = mongoTemplate.getCollection(mongoTemplate.getCollectionName(Program.class)).updateMany(
                    Filters.eq("tracks.key", SCREEN),
                    Updates.pull("tracks", new Document("key", SCREEN))).getModifiedCount();
            long reviews = mongoTemplate.getCollection(mongoTemplate.getCollectionName(ProgramReview.class)).updateMany(
                    Filters.eq("changes.track", SCREEN),
                    Updates.pull("changes", new Document("track", SCREEN))).getModifiedCount();

            if (urges + deleted + programs + reviews > 0) {
                log.info("Screen-track migration: {} urge log(s) refiled under 'urge', {} screen log(s) deleted, "
                        + "the track removed from {} program(s), screen changes dropped from {} review(s).",
                        urges, deleted, programs, reviews);
            } else {
                log.debug("Screen-track migration: nothing to retire.");
            }
        } catch (RuntimeException e) {
            log.warn("Screen-track migration failed — continuing startup without it: {}", e.getMessage());
        }
    }
}
