package com.personal_dashboard.backend.util;

import org.junit.jupiter.api.Test;

import static com.personal_dashboard.backend.util.ShoppingCategories.*;
import static org.junit.jupiter.api.Assertions.*;

class ShoppingCategoriesTest {

    @Test
    void filesCommonItemsByName() {
        assertEquals(PRODUCE, guess("Tomatoes"));
        assertEquals(PRODUCE, guess("bananas"));
        assertEquals(DAIRY, guess("Milk"));
        assertEquals(DAIRY, guess("paneer"));
        assertEquals(BAKERY, guess("brown bread"));
        assertEquals(GRAINS, guess("basmati rice"));
        assertEquals(GRAINS, guess("toor dal"));
        assertEquals(SPICES, guess("turmeric powder"));
        assertEquals(PANTRY, guess("sunflower oil"));
        assertEquals(SNACKS, guess("Digestive biscuits"));
        assertEquals(BEVERAGES, guess("orange juice"));
        assertEquals(HOUSEHOLD, guess("dishwash liquid"));
        assertEquals(PERSONAL_CARE, guess("shampoo"));
    }

    @Test
    void theHeadNounDecidesWhenAWordPointsTwoWays() {
        // "tomato" is produce, but a tomato ketchup is a ketchup; "coconut" likewise.
        assertEquals(PANTRY, guess("tomato ketchup"));
        assertEquals(PANTRY, guess("coconut oil"));
        assertEquals(DAIRY, guess("chocolate milk"));
    }

    @Test
    void phrasesBeatTheirParts() {
        assertEquals(FROZEN, guess("ice cream"));
        assertEquals(FROZEN, guess("frozen peas"));
        assertEquals(PANTRY, guess("peanut butter"));
        assertEquals(PANTRY, guess("coconut milk"));
        assertEquals(BEVERAGES, guess("tea bags"));
        assertEquals(HOUSEHOLD, guess("toilet paper"));
        assertEquals(SPICES, guess("black pepper"));
        assertEquals(PRODUCE, guess("bell pepper"));
    }

    @Test
    void ignoresQuantitiesPunctuationAndCase() {
        assertEquals(PRODUCE, guess("2 KG Onions!"));
        assertEquals(DAIRY, guess("  milk  (1 L)"));
    }

    @Test
    void handlesPlurals() {
        assertEquals(PRODUCE, guess("potatoes"));
        assertEquals(PRODUCE, guess("mangoes"));
        assertEquals(PRODUCE, guess("onions"));
        assertEquals(SNACKS, guess("cookies"));
    }

    @Test
    void unknownOrEmptyFallsBackToOther() {
        assertEquals(OTHER, guess("thingamajig"));
        assertEquals(OTHER, guess("   "));
        assertEquals(OTHER, guess(null));
        assertEquals(OTHER, guess("123"));
    }

    @Test
    void everyGuessIsAKnownCategory() {
        for (String name : new String[]{"milk", "bread", "xyz", "soap", "ghee", "chips"}) {
            assertTrue(isValid(guess(name)), name);
        }
    }

    @Test
    void normalizeAcceptsKeysInAnyCaseAndRejectsTheRest() {
        assertEquals(PERSONAL_CARE, normalize(" personal care "));
        assertEquals(DAIRY, normalize("dairy"));
        assertNull(normalize("PLUMBING"));
        assertNull(normalize(null));
    }

    @Test
    void aisleOrderIsStableAndOtherIsLast() {
        assertEquals(PRODUCE, ORDER.get(0));
        assertEquals(OTHER, ORDER.get(ORDER.size() - 1));
        assertTrue(orderOf(DAIRY) < orderOf(FROZEN));
        assertEquals(ORDER.size(), orderOf("NOPE"));
    }
}
