package com.personal_dashboard.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personal_dashboard.backend.model.DailyTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * REST client for the Google Tasks API (tasks.googleapis.com/tasks/v1).
 *
 * <p>The Tasks API is <em>not</em> a second Calendar API, and three differences drive
 * almost every design choice in the sync built on top of it:
 *
 * <ul>
 *   <li><b>No push notifications.</b> Calendar has watch channels; Tasks has nothing.
 *       Inbound changes are only ever discovered by polling with {@code updatedMin}.</li>
 *   <li><b>No sync tokens.</b> There is no {@code nextSyncToken} and so no 410-and-resync
 *       protocol — the incremental cursor is a timestamp we keep ourselves.</li>
 *   <li><b>No client-supplied ids.</b> Calendar lets us send a deterministic id and get a
 *       409 on a retried insert; Tasks always generates the id, so a lost insert response
 *       genuinely can duplicate. The mapping row is written immediately after insert to
 *       keep that window as small as possible.</li>
 * </ul>
 *
 * <p>And one field-level trap: {@code due} is documented as an RFC 3339 timestamp but
 * <em>only the date survives</em> — Google discards the time of day, and the API has no
 * other field for it. The time is therefore carried in the title as a {@code HH:mm · }
 * prefix (see {@link GoogleTaskTitle}), which is the only position a phone widget
 * reliably shows, and is decoded back on the way in so it round-trips.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GoogleTasksClient {

    private static final String BASE = "https://tasks.googleapis.com/tasks/v1";
    /** The API caps a page at 100; the default is 20, which would silently triple our call count. */
    private static final int PAGE_SIZE = 100;

    private final GoogleApiExecutor executor;
    private final ObjectMapper objectMapper;

    // ─── Task lists ──────────────────────────────────────────────────────────────

    /** All task lists on the account, following pagination to the end. */
    public List<JsonNode> listTaskLists(String storeId) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        String pageToken = null;
        do {
            StringBuilder url = new StringBuilder(BASE + "/users/@me/lists?maxResults=" + PAGE_SIZE);
            if (pageToken != null) {
                url.append("&pageToken=").append(encode(pageToken));
            }
            JsonNode body = getJson(storeId, url.toString(), "listTaskLists");
            addItems(body, all);
            pageToken = text(body, "nextPageToken");
        } while (pageToken != null);
        return all;
    }

    /** Create a task list and return its Google id. */
    public String insertTaskList(String storeId, String title) throws Exception {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("title", title);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/users/@me/lists"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

        HttpResponse<String> response = executor.execute(storeId, rb);
        if (!isOk(response)) {
            throw new RuntimeException("Failed to create Google task list '" + title + "': " + response.body());
        }
        return objectMapper.readTree(response.body()).get("id").asText();
    }

    /**
     * Fetch one task list. Pass {@code "@default"} to resolve the user's primary list —
     * the one the widget opens on — into its real id. Resolving it matters: storing the
     * literal "@default" in a mapping would never compare equal to the concrete id the
     * list endpoint returns, and every push would then think the list had changed and
     * delete-and-reinsert the task. Returns null when the list is gone.
     */
    public JsonNode getTaskList(String storeId, String taskListId) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/users/@me/lists/" + encode(taskListId)))
                .GET();
        HttpResponse<String> response = executor.execute(storeId, rb);
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            return null;
        }
        if (!isOk(response)) {
            throw new RuntimeException("Google tasklists.get failed for " + taskListId + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Delete a task list. Destructive and irreversible in Google, so callers must have
     * checked the list is empty and is not the user's default list.
     */
    public void deleteTaskList(String storeId, String taskListId) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/users/@me/lists/" + encode(taskListId)))
                .DELETE();
        HttpResponse<String> response = executor.execute(storeId, rb);
        int code = response.statusCode();
        if (!isOk(response) && code != 404 && code != 410) {
            throw new RuntimeException("Google tasklists.delete failed for " + taskListId + ": " + response.body());
        }
    }

    /** Count the live (not deleted) tasks in a list — the emptiness check before deleting it. */
    public int countLiveTasks(String storeId, String taskListId) throws Exception {
        int count = 0;
        String pageToken = null;
        do {
            TaskPage page = listTasks(storeId, taskListId, null, pageToken);
            if (page.isListMissing()) {
                return 0;
            }
            for (JsonNode item : page.getItems()) {
                if (!item.path("deleted").asBoolean(false)) {
                    count++;
                }
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        return count;
    }

    /** Rename a task list. Best-effort: a failure here never blocks a sync. */
    public void renameTaskList(String storeId, String taskListId, String title) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("title", title);
            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + "/users/@me/lists/" + encode(taskListId)))
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload.toString()));
            HttpResponse<String> response = executor.execute(storeId, rb);
            if (!isOk(response)) {
                log.warn("Could not rename Google task list {}: {}", taskListId, response.body());
            }
        } catch (Exception e) {
            log.warn("Could not rename Google task list {}: {}", taskListId, e.getMessage());
        }
    }

    // ─── Tasks ───────────────────────────────────────────────────────────────────

    /** One page of tasks plus the cursor to the next. */
    @Getter
    public static class TaskPage {
        private final List<JsonNode> items;
        private final String nextPageToken;
        /** True when the list itself is gone from Google (404) — not an error, a state. */
        private final boolean listMissing;

        public TaskPage(List<JsonNode> items, String nextPageToken, boolean listMissing) {
            this.items = items;
            this.nextPageToken = nextPageToken;
            this.listMissing = listMissing;
        }
    }

    /**
     * List tasks in a list, optionally only those modified since {@code updatedMin}.
     *
     * <p>{@code showCompleted}, {@code showHidden} and {@code showDeleted} are all sent as
     * true, and all three are load-bearing:
     * <ul>
     *   <li>{@code showHidden} is the one that bites. Google's own clients mark a task
     *       <em>hidden</em> when it is completed, and hidden tasks are excluded by default —
     *       so without it, ticking a task off on the phone makes it vanish from the API
     *       entirely and the completion is never synced back. {@code showCompleted} alone
     *       is not enough; the docs say so explicitly.</li>
     *   <li>{@code showDeleted} is the only way a deletion made on the phone is ever
     *       observable. Without it a deleted task simply stops being returned, which is
     *       indistinguishable from "unchanged".</li>
     * </ul>
     */
    public TaskPage listTasks(String storeId, String taskListId, Instant updatedMin, String pageToken) throws Exception {
        StringBuilder url = new StringBuilder(BASE + "/lists/" + encode(taskListId) + "/tasks")
                .append("?maxResults=").append(PAGE_SIZE)
                .append("&showCompleted=true&showHidden=true&showDeleted=true");
        if (updatedMin != null) {
            url.append("&updatedMin=").append(encode(updatedMin.toString()));
        }
        if (pageToken != null) {
            url.append("&pageToken=").append(encode(pageToken));
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder().uri(URI.create(url.toString())).GET();
        HttpResponse<String> response = executor.execute(storeId, rb);

        if (response.statusCode() == 404) {
            log.warn("Google task list {} no longer exists on account {}", taskListId, storeId);
            return new TaskPage(List.of(), null, true);
        }
        if (!isOk(response)) {
            throw new RuntimeException("Google tasks.list failed for list " + taskListId + ": " + response.body());
        }

        JsonNode body = objectMapper.readTree(response.body());
        List<JsonNode> items = new ArrayList<>();
        addItems(body, items);
        return new TaskPage(items, text(body, "nextPageToken"), false);
    }

    /** Insert a task into a list; returns the created resource (id, etag, updated). */
    public JsonNode insertTask(String storeId, String taskListId, DailyTask task) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/lists/" + encode(taskListId) + "/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildTaskNode(task).toString()));

        HttpResponse<String> response = executor.execute(storeId, rb);
        if (!isOk(response)) {
            throw new RuntimeException("Google tasks.insert failed: " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /**
     * Patch an existing task. Returns the updated resource, or null when Google no longer
     * has it (404) — the caller re-inserts rather than treating that as a failure.
     */
    public JsonNode patchTask(String storeId, String taskListId, String googleTaskId, DailyTask task) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/lists/" + encode(taskListId) + "/tasks/" + encode(googleTaskId)))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(buildTaskNode(task).toString()));

        HttpResponse<String> response = executor.execute(storeId, rb);
        if (response.statusCode() == 404 || response.statusCode() == 410) {
            return null;
        }
        if (!isOk(response)) {
            throw new RuntimeException("Google tasks.patch failed for " + googleTaskId + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    /** Delete a task. 404/410 is success — it is already gone. */
    public void deleteTask(String storeId, String taskListId, String googleTaskId) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/lists/" + encode(taskListId) + "/tasks/" + encode(googleTaskId)))
                .DELETE();

        HttpResponse<String> response = executor.execute(storeId, rb);
        int code = response.statusCode();
        if (!isOk(response) && code != 404 && code != 410) {
            throw new RuntimeException("Google tasks.delete failed for " + googleTaskId + ": " + response.body());
        }
    }

    /**
     * Probe the account for the Tasks scope. Returns true when the grant covers Tasks,
     * false when Google reports the scope is missing. Used to show "reconnect to enable"
     * instead of failing silently on every poll.
     */
    public boolean hasTasksScope(String storeId) {
        try {
            listTaskLists(storeId);
            return true;
        } catch (GoogleApiExecutor.MissingScopeException e) {
            return false;
        } catch (Exception e) {
            log.warn("Could not probe Tasks scope for {}: {}", storeId, e.getMessage());
            return false;
        }
    }

    // ─── Mapping ─────────────────────────────────────────────────────────────────

    /**
     * Build the Google Tasks body for a local task.
     *
     * <p>Only four fields are writable and meaningful here: title, notes, due and status.
     * {@code completed}, {@code position}, {@code parent} and {@code hidden} are server-owned —
     * sending {@code completed} is redundant (Google stamps it from {@code status}) and
     * sending the others is rejected or ignored.
     */
    ObjectNode buildTaskNode(DailyTask task) {
        ObjectNode node = objectMapper.createObjectNode();
        // The time of day rides in the title (see GoogleTaskTitle): the API has no field
        // for it, and the title prefix is the only position a phone widget reliably shows.
        // Driven by whether a time exists, not by the allDay flag: the two are redundant,
        // and trusting the flag would silently drop the time on any row where it is stale.
        // GoogleTaskTitle.encode already omits the prefix for a null or unparseable time.
        String time = task.getScheduledTime() != null ? task.getScheduledTime() : task.getStartTime();
        node.put("title", GoogleTaskTitle.encode(time, task.getTitle()));
        node.put("notes", task.getNotes() == null ? "" : task.getNotes());

        LocalDate date = task.getDate();
        if (date != null) {
            // `due` carries the date only — Google discards the time portion on write.
            // Midnight UTC is the canonical form Google's own clients send.
            node.put("due", date.atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        }
        node.put("status", Boolean.TRUE.equals(task.getCompleted()) ? "completed" : "needsAction");
        return node;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private JsonNode getJson(String storeId, String url, String what) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder().uri(URI.create(url)).GET();
        HttpResponse<String> response = executor.execute(storeId, rb);
        if (!isOk(response)) {
            throw new RuntimeException("Google " + what + " failed: " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private void addItems(JsonNode body, List<JsonNode> sink) {
        if (body != null && body.has("items") && body.get("items").isArray()) {
            body.get("items").forEach(sink::add);
        }
    }

    private static boolean isOk(HttpResponse<String> response) {
        int code = response.statusCode();
        return code >= 200 && code < 300;
    }

    static String text(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        String value = node.get(field).asText();
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }
}
