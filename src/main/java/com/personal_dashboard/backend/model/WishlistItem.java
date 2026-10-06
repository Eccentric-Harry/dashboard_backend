package com.personal_dashboard.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A thing the user is thinking of buying (/shopping, Wishlist tab): a product card with where
 * it would come from. The photo is never stored — {@link #imageUrl} points at the store's own
 * image, read from the product page by {@code ProductLinkPreviewer} or pasted by the user.
 *
 * <p>Money is never moved here. "Bought it" writes an ordinary ledger row (through
 * {@code SavingsGoalService.buy} when the wish has a savings goal, so the saved money is
 * released and the purchase stays off the budget); "Save up for it" creates a savings goal
 * and remembers its id.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "wishlist_items")
public class WishlistItem implements UserOwnedDocument {

    public static final String WANTED = "WANTED";
    public static final String BOUGHT = "BOUGHT";
    /** Decided against it. Kept so "not spent by letting go" can be shown, and reversible. */
    public static final String LET_GO = "LET_GO";

    public static final String NEED = "NEED";
    public static final String WANT = "WANT";

    @Id
    private String id;

    private String userId;

    private String name;

    /** The product page. Optional — a wish can be "a good pair of running shoes" from anywhere. */
    private String url;

    /** Where it would be bought: "Amazon", "Decathlon Kondapur". Derived from the link when not typed. */
    private String store;

    /** A remote image URL (the store's CDN). Never downloaded or stored. */
    private String imageUrl;

    /** The latest known price. */
    private BigDecimal price;

    /** The price when it was first known — the baseline for "₹500 less than when you added it". */
    private BigDecimal firstPrice;

    /** When the price was last read from the page. */
    private Instant priceCheckedAt;

    /** NEED | WANT. Wants get the cooling-off clock on the client; needs don't. */
    private String priority;

    private String note;

    /** WANTED | BOUGHT | LET_GO. */
    private String status;

    /** Set when bought: the day (yyyy-MM-dd), what it really cost, and the ledger row it wrote (when one was direct). */
    private String boughtOn;
    private BigDecimal boughtFor;
    private String transactionId;

    /** The savings goal created for it with "Save up for it", if any. */
    private String savingsGoalId;

    /** When it was bought or let go. */
    private Instant closedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
