package com.personal_dashboard.backend.controller;

import com.personal_dashboard.backend.dto.ApiMeta;
import com.personal_dashboard.backend.dto.ApiResponse;
import com.personal_dashboard.backend.dto.ShoppingCheckoutDTO;
import com.personal_dashboard.backend.dto.ShoppingItemDTO;
import com.personal_dashboard.backend.dto.ShoppingSuggestionDTO;
import com.personal_dashboard.backend.dto.request.ShoppingCheckedRequest;
import com.personal_dashboard.backend.dto.request.ShoppingCheckoutRequest;
import com.personal_dashboard.backend.dto.request.ShoppingForgetRequest;
import com.personal_dashboard.backend.dto.request.ShoppingItemRequest;
import com.personal_dashboard.backend.dto.request.ShoppingItemsBatchRequest;
import com.personal_dashboard.backend.service.ShoppingListService;
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
 * The grocery list (/shopping, Groceries tab). Request/response mapping only — filing into aisles,
 * de-duplication, memory, the basket and checkout live in {@link ShoppingListService}.
 */
@RestController
@RequestMapping("/api/v1/shopping")
@RequiredArgsConstructor
public class ShoppingController {

    private final ShoppingListService shoppingListService;

    @GetMapping("/items")
    @Operation(summary = "The whole list, in aisle order",
            description = "Category, then to-get before in-basket, then oldest first.")
    public ResponseEntity<ApiResponse<List<ShoppingItemDTO>>> list() {
        return ResponseEntity.ok(wrap(shoppingListService.list()));
    }

    @PostMapping("/items")
    @Operation(summary = "Add an item",
            description = "Filed by name when no category is given. An item already on the list is returned "
                    + "as it is; one in the basket comes back onto the list.")
    public ResponseEntity<ApiResponse<ShoppingItemDTO>> add(@Valid @RequestBody ShoppingItemRequest request) {
        ShoppingItemDTO item = shoppingListService.addAll(List.of(request)).get(0);
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(item));
    }

    @PostMapping("/items/batch")
    @Operation(summary = "Add several items at once (up to 50)",
            description = "One result per request, in request order. Also restores a cleared basket.")
    public ResponseEntity<ApiResponse<List<ShoppingItemDTO>>> addBatch(
            @Valid @RequestBody ShoppingItemsBatchRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(wrap(shoppingListService.addAll(request.getItems())));
    }

    @PutMapping("/items/{id}")
    @Operation(summary = "Edit an item's name, category, quantity and note")
    public ResponseEntity<ApiResponse<ShoppingItemDTO>> update(
            @PathVariable String id, @Valid @RequestBody ShoppingItemRequest request) {
        return ResponseEntity.ok(wrap(shoppingListService.update(id, request)));
    }

    @PatchMapping("/items/{id}/checked")
    @Operation(summary = "Move an item into the basket or back onto the list")
    public ResponseEntity<ApiResponse<ShoppingItemDTO>> setChecked(
            @PathVariable String id, @Valid @RequestBody ShoppingCheckedRequest request) {
        return ResponseEntity.ok(wrap(shoppingListService.setChecked(id, request.getChecked())));
    }

    @DeleteMapping("/items/{id}")
    @Operation(summary = "Remove an item")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        shoppingListService.delete(id);
        return ResponseEntity.ok(wrap(null));
    }

    @PostMapping("/items/clear-checked")
    @Operation(summary = "Empty the basket", description = "Returns the removed items so the client can undo.")
    public ResponseEntity<ApiResponse<List<ShoppingItemDTO>>> clearChecked() {
        return ResponseEntity.ok(wrap(shoppingListService.clearChecked()));
    }

    @PostMapping("/items/checkout")
    @Operation(summary = "Done shopping — empty the basket and optionally log the shop in Finance",
            description = "With an amount, writes one Expense (default category Groceries) before clearing.")
    public ResponseEntity<ApiResponse<ShoppingCheckoutDTO>> checkout(@Valid @RequestBody ShoppingCheckoutRequest request) {
        return ResponseEntity.ok(wrap(shoppingListService.checkout(request)));
    }

    @GetMapping("/suggestions")
    @Operation(summary = "Buy again — remembered items not on the list, most-bought and recent first")
    public ResponseEntity<ApiResponse<List<ShoppingSuggestionDTO>>> suggestions() {
        return ResponseEntity.ok(wrap(shoppingListService.suggestions()));
    }

    @PostMapping("/suggestions/forget")
    @Operation(summary = "Stop suggesting an item")
    public ResponseEntity<ApiResponse<Void>> forget(@Valid @RequestBody ShoppingForgetRequest request) {
        shoppingListService.forget(request.getName());
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
