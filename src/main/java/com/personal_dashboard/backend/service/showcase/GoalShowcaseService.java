package com.personal_dashboard.backend.service.showcase;

import com.personal_dashboard.backend.dto.SavingsGoalDTO;
import com.personal_dashboard.backend.dto.request.ShowcaseUpdateRequest;
import com.personal_dashboard.backend.model.GoalPhoto;
import com.personal_dashboard.backend.model.GoalShowcase;
import com.personal_dashboard.backend.model.SavingsGoal;
import com.personal_dashboard.backend.repository.SavingsGoalRepository;
import com.personal_dashboard.backend.service.SavingsGoalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A savings goal's showcase — what it looks like, so the goal page pulls you toward the
 * thing rather than the arithmetic. Photos come from a product page (found by name, or
 * pasted), each measured on the way in; the user curates the order and adds their own
 * reasons. Nothing here moves money or touches the plan.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalShowcaseService {

    static final int MAX_PHOTOS = 24;

    private final SavingsGoalService savingsGoalService;
    private final SavingsGoalRepository repository;
    private final ProductPageScraper scraper;
    private final ProductPageFinder finder;

    /**
     * With a link: a page replaces the photos and highlights with its own (reasons stay), an
     * image link adds that photo. Without one: the official page is looked up by name first.
     */
    public SavingsGoalDTO find(String id, String link) {
        SavingsGoal goal = savingsGoalService.requireOwned(id);
        ProductPageScraper.Scraped page = link != null && !link.isBlank()
                ? scrapeOrExplain(SafeWebClient.parseLink(link))
                : findByName(goal);

        GoalShowcase current = goal.getShowcase();
        GoalShowcase next;
        if (page.image()) {
            if (page.photos().isEmpty()) throw new IllegalArgumentException("That image is too small to show");
            next = current != null ? current : GoalShowcase.builder().build();
            List<GoalPhoto> photos = new ArrayList<>(nullSafe(next.getPhotos()));
            GoalPhoto added = page.photos().get(0);
            photos.removeIf(p -> Objects.equals(p.getUrl(), added.getUrl()));
            if (photos.size() >= MAX_PHOTOS) throw new IllegalArgumentException("That's " + MAX_PHOTOS + " photos — remove one first");
            photos.add(added);
            next.setPhotos(photos);
        } else {
            if (page.photos().isEmpty()) {
                throw new IllegalArgumentException("No photos big enough on " + ProductPageScraper.sourceName(page.finalUrl())
                        + " — try the maker's own product page");
            }
            next = GoalShowcase.builder()
                    .sourceUrl(page.finalUrl().toString())
                    .sourceName(ProductPageScraper.sourceName(page.finalUrl()))
                    .title(page.title())
                    .highlights(new ArrayList<>(page.highlights()))
                    .photos(new ArrayList<>(page.photos()))
                    .reasons(current != null ? new ArrayList<>(nullSafe(current.getReasons())) : new ArrayList<>())
                    .build();
        }
        next.setFetchedAt(Instant.now());
        goal.setShowcase(next);
        SavingsGoal saved = repository.save(goal);
        log.info("Showcase for goal {} '{}': {} photos from {}", id, goal.getName(), next.getPhotos().size(),
                page.image() ? "an image link" : next.getSourceName());
        return savingsGoalService.view(saved);
    }

    /** The goal's official page: Apple's predictable address first (free), then a web search by name. */
    private ProductPageScraper.Scraped findByName(SavingsGoal goal) {
        Optional<URI> guess = ProductPageFinder.appleGuess(goal.getName());
        if (guess.isPresent()) {
            try {
                ProductPageScraper.Scraped page = scraper.scrape(guess.get());
                if (!page.photos().isEmpty()) return page;
            } catch (IOException e) {
                log.info("Showcase: no Apple page at {} ({})", guess.get(), e.getMessage());
            }
        }
        if (!finder.available()) {
            throw new IllegalArgumentException("Paste the product's link — finding it by name needs an AI key on the server");
        }
        Optional<URI> found;
        try {
            found = finder.find(goal.getName(), goal.getKind());
        } catch (RuntimeException e) {
            log.warn("Showcase page search failed for goal {}: {}", goal.getId(), e.getMessage());
            throw new IllegalArgumentException("Couldn't search right now — paste the product's link instead");
        }
        URI target = found.orElseThrow(() -> new IllegalArgumentException(
                "Couldn't find an official page for " + goal.getName() + " — paste its link instead"));
        return scrapeOrExplain(target);
    }

    private ProductPageScraper.Scraped scrapeOrExplain(URI target) {
        try {
            return scraper.scrape(target);
        } catch (IOException e) {
            log.info("Showcase fetch failed ({}): {}", target.getHost(), e.getMessage());
            throw new IllegalArgumentException("Couldn't open " + ProductPageScraper.sourceName(target) + " — try another link");
        }
    }

    /** Reorder or remove photos and highlights, replace the reasons. An emptied showcase is cleared. */
    public SavingsGoalDTO update(String id, ShowcaseUpdateRequest request) {
        SavingsGoal goal = savingsGoalService.requireOwned(id);
        GoalShowcase showcase = goal.getShowcase() != null ? goal.getShowcase() : GoalShowcase.builder().build();

        if (request.getPhotos() != null) {
            Map<String, GoalPhoto> known = new LinkedHashMap<>();
            for (GoalPhoto photo : nullSafe(showcase.getPhotos())) known.put(photo.getUrl(), photo);
            List<GoalPhoto> ordered = new ArrayList<>();
            for (String url : request.getPhotos()) {
                GoalPhoto photo = known.remove(url);
                if (photo != null) ordered.add(photo);
            }
            showcase.setPhotos(ordered);
        }
        if (request.getHighlights() != null) {
            List<String> kept = nullSafe(showcase.getHighlights());
            showcase.setHighlights(new ArrayList<>(request.getHighlights().stream().filter(kept::contains).distinct().toList()));
        }
        if (request.getReasons() != null) {
            showcase.setReasons(new ArrayList<>(request.getReasons().stream()
                    .filter(Objects::nonNull)
                    .map(r -> r.trim().replaceAll("\\s+", " "))
                    .filter(r -> !r.isEmpty())
                    .distinct()
                    .toList()));
        }

        boolean empty = nullSafe(showcase.getPhotos()).isEmpty() && nullSafe(showcase.getHighlights()).isEmpty()
                && nullSafe(showcase.getReasons()).isEmpty();
        goal.setShowcase(empty ? null : showcase);
        return savingsGoalService.view(repository.save(goal));
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
