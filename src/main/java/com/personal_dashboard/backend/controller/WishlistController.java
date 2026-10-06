package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.LinkPreviewDTO;
import com.personal_dashboard.backend.dto.WishlistItemDTO;
import com.personal_dashboard.backend.dto.request.LinkPreviewRequest;
import com.personal_dashboard.backend.dto.request.WishlistBuyRequest;
import com.personal_dashboard.backend.dto.request.WishlistItemRequest;
import com.personal_dashboard.backend.service.WishlistService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wishlist (/shopping, Wishlist tab). Request/response mapping only — link reading, the
 * Finance hand-offs and the goal sync live in {@link WishlistService}.
 */
@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final WishlistService wishlistService;

    @GetMapping
    @Operation(summary = "Every wish — open ones first (needs, then newest), then bought / let go")
    public ResponseEntity<ApiResponse<List<WishlistItemDTO>>> list() {
        return ResponseEntity.ok(wrap(wishlistService.list()));
    }

    @PostMapping("/preview")
    @Operation(summary = "Read a product link (name, photo URL, store, price) to prefill the form",
            description = "Never stores anything. Falls back to what the URL itself says when the store blocks the read.")
    public ResponseEntity<ApiResponse<LinkPreviewDTO>> preview(@Valid @RequestBody LinkPreviewRequest request) {
        return ResponseEntity.ok(wrap(wishlistService.preview(request.getUrl())));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<WishlistItemDTO>> create(@Valid @RequestBody WishlistItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(wishlistService.create(request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<WishlistItemDTO>> update(
            @PathVariable String id, @Valid @RequestBody WishlistItemRequest request) {
        return ResponseEntity.ok(wrap(wishlistService.update(id, request)));
    }

    @PostMapping("/{id}/refresh")
    @Operation(summary = "Re-read the product page for a fresh price (the first price stays as the baseline)")
    public ResponseEntity<ApiResponse<WishlistItemDTO>> refresh(@PathVariable String id) {
        return ResponseEntity.ok(wrap(wishlistService.refresh(id)));
    }

    @PostMapping("/{id}/buy")
    @Operation(summary = "Bought it — logs the purchase in Finance",
            description = "Through the linked savings goal when there is one (released + off-budget), else a plain Expense.")
    public ResponseEntity<ApiResponse<WishlistItemDTO>> buy(
            @PathVariable String id, @Valid @RequestBody WishlistBuyRequest request) {
        return ResponseEntity.ok(wrap(wishlistService.buy(id, request)));
    }

    @PostMapping("/{id}/save-for")
    @Operation(summary = "Save up for it — creates a 'Saving for' goal on /finance from the wish")
    public ResponseEntity<ApiResponse<WishlistItemDTO>> saveFor(@PathVariable String id) {
        return ResponseEntity.ok(wrap(wishlistService.saveFor(id)));
    }

    @PostMapping("/{id}/let-go")
    public ResponseEntity<ApiResponse<WishlistItemDTO>> letGo(@PathVariable String id) {
        return ResponseEntity.ok(wrap(wishlistService.letGo(id)));
    }

    @PostMapping("/{id}/reopen")
    @Operation(summary = "Back onto the wishlist (a logged purchase stays in the ledger)")
    public ResponseEntity<ApiResponse<WishlistItemDTO>> reopen(@PathVariable String id) {
        return ResponseEntity.ok(wrap(wishlistService.reopen(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        wishlistService.delete(id);
        return ResponseEntity.ok(wrap(null));
    }

    private static <T> ApiResponse<T> wrap(T data) {
        ApiMeta meta = ApiMeta.builder()
                .requestId(UUID.randomUUID().toString())
                .timestamp(Instant.now().toString())
                .source("api")
                .build();
        return ApiResponse.<T>builder().data(data).meta(meta).build();
    }
}
