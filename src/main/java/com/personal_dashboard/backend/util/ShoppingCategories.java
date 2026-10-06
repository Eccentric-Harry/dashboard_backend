package com.personal_dashboard.backend.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The shopping list's categories, in the order a shop is walked, and the guess that files a
 * newly typed item under one so the user never has to pick. Mirrored by
 * {@code dashboard_ui/src/types/shopping.ts} (labels, icons, hues) — a key added here must be
 * added there; keys are permanent once used.
 *
 * <p>The guess is deliberately simple and explainable: a few multi-word phrases that mean
 * something other than their last word ("ice cream", "peanut butter"), then the item's head
 * noun — the last word, as in English "tomato ketchup" is a ketchup — then any other word,
 * right to left. Anything unrecognised is {@link #OTHER}; the user can always move it.
 */
public final class ShoppingCategories {

    public static final String PRODUCE = "PRODUCE";
    public static final String DAIRY = "DAIRY";
    public static final String BAKERY = "BAKERY";
    public static final String GRAINS = "GRAINS";
    public static final String SPICES = "SPICES";
    public static final String PANTRY = "PANTRY";
    public static final String SNACKS = "SNACKS";
    public static final String BEVERAGES = "BEVERAGES";
    public static final String FROZEN = "FROZEN";
    public static final String HOUSEHOLD = "HOUSEHOLD";
    public static final String PERSONAL_CARE = "PERSONAL_CARE";
    public static final String OTHER = "OTHER";

    /** Aisle order: the order the list is shown in, and the walk through the shop. */
    public static final List<String> ORDER = List.of(
            PRODUCE, DAIRY, BAKERY, GRAINS, SPICES, PANTRY, SNACKS, BEVERAGES,
            FROZEN, HOUSEHOLD, PERSONAL_CARE, OTHER);

    private static final Pattern NON_WORD = Pattern.compile("[^a-z]+");

    /** Phrases whose meaning is not their last word's. Checked first, as whole-word sequences. */
    private static final Map<String, String> PHRASES = new HashMap<>();
    /** Single words → category. */
    private static final Map<String, String> WORDS = new HashMap<>();

    static {
        phrases(FROZEN, "ice cream", "frozen peas", "frozen corn", "french fries", "frozen yogurt");
        phrases(PANTRY, "peanut butter", "almond butter", "tomato puree", "tomato paste", "coconut milk",
                "soy sauce", "chilli sauce", "chili sauce", "apple cider vinegar");
        phrases(BEVERAGES, "tea bag", "tea bags", "green tea", "black tea", "cold coffee", "coconut water", "soda water");
        phrases(HOUSEHOLD, "toilet paper", "toilet roll", "tissue paper", "kitchen roll", "paper towel",
                "dish soap", "dishwash liquid", "washing powder", "floor cleaner", "bin bag", "garbage bag",
                "trash bag", "aluminium foil", "cling film");
        phrases(PERSONAL_CARE, "tooth paste", "tooth brush", "hand wash", "hand soap", "body wash",
                "face wash", "hair oil", "sanitary pad", "shaving cream", "lip balm");
        phrases(DAIRY, "cream cheese", "butter milk", "sour cream", "almond milk", "oat milk", "soy milk");
        phrases(SPICES, "black pepper", "red chilli", "chilli powder", "curry leaves", "bay leaf", "bay leaves");
        phrases(PRODUCE, "bell pepper", "curry leaf", "green chilli", "green chillies", "spring onion", "sweet potato");
        phrases(GRAINS, "chana dal", "toor dal", "moong dal", "urad dal", "masoor dal");

        words(PRODUCE, "tomato", "onion", "potato", "garlic", "ginger", "carrot", "cucumber", "spinach",
                "cabbage", "cauliflower", "broccoli", "capsicum", "brinjal", "eggplant", "okra",
                "bhindi", "peas", "beans", "corn", "beetroot", "radish", "pumpkin", "gourd", "lemon", "lime",
                "coriander", "cilantro", "mint", "methi", "palak", "lettuce", "mushroom", "zucchini",
                "banana", "apple", "mango", "orange", "grapes", "grape", "papaya", "pomegranate",
                "watermelon", "melon", "pineapple", "guava", "pear", "kiwi", "strawberry", "strawberries",
                "blueberry", "blueberries", "berries", "coconut", "avocado", "fruit", "fruits", "vegetable",
                "vegetables", "veggies", "greens", "herbs", "chilli", "chillies", "chili", "sapota", "chikoo",
                "dates", "fig", "figs", "plum", "peach", "cherry", "cherries", "drumstick", "raw banana");
        words(DAIRY, "milk", "curd", "yogurt", "yoghurt", "dahi", "paneer", "cheese", "butter", "ghee",
                "cream", "buttermilk", "lassi", "egg", "eggs", "khoa", "mawa");
        words(BAKERY, "bread", "bun", "buns", "pav", "roll", "rolls", "cake", "croissant", "bagel", "toast",
                "muffin", "muffins", "baguette", "rusk", "naan", "pizza base", "tortilla", "wrap", "wraps");
        words(GRAINS, "rice", "atta", "flour", "maida", "wheat", "dal", "dals", "daal", "lentil", "lentils",
                "pulses", "rajma", "chickpea", "chickpeas", "chana", "besan", "rava", "sooji", "semolina",
                "poha", "oats", "oat", "quinoa", "millet", "ragi", "jowar", "bajra", "barley", "vermicelli",
                "pasta", "noodles", "macaroni", "spaghetti", "cornflakes", "muesli", "granola", "cereal",
                "moong", "toor", "urad", "masoor", "lobia", "soya");
        words(SPICES, "salt", "turmeric", "haldi", "cumin", "jeera", "coriander powder", "masala", "garam",
                "cardamom", "elaichi", "cinnamon", "clove", "cloves", "pepper powder", "mustard", "hing",
                "pepper", "asafoetida", "saffron", "kesar", "fenugreek", "ajwain", "oregano", "paprika", "spice", "spices",
                "chaat", "seasoning", "fennel", "saunf", "nutmeg", "tamarind", "imli");
        words(PANTRY, "oil", "sugar", "jaggery", "honey", "vinegar", "sauce", "ketchup", "mayonnaise", "mayo",
                "jam", "spread", "pickle", "achar", "chutney", "syrup", "nuts", "almond", "almonds", "cashew",
                "cashews", "walnut", "walnuts", "pistachio", "pistachios", "raisin", "raisins", "peanut",
                "peanuts", "seeds", "puree", "stock", "baking", "yeast", "cornstarch", "cornflour", "cocoa",
                "papad", "canned", "tinned", "dryfruits");
        words(SNACKS, "chips", "biscuit", "biscuits", "cookie", "cookies", "chocolate", "chocolates", "namkeen",
                "bhujia", "popcorn", "crackers", "candy", "sweets", "mithai", "wafers", "snack", "snacks",
                "makhana", "chikki", "kurkure", "maggi");
        words(BEVERAGES, "tea", "coffee", "juice", "water", "soda", "cola", "coke", "pepsi", "lemonade",
                "squash", "smoothie", "drink", "drinks", "beer", "wine", "whisky", "rum", "vodka", "milkshake",
                "bournvita", "horlicks", "boost", "chai", "kombucha", "sprite");
        words(FROZEN, "frozen", "icecream", "gelato", "sorbet", "popsicle", "nuggets");
        words(HOUSEHOLD, "detergent", "soap", "cleaner", "bleach", "sponge", "scrub", "mop", "broom", "bulb",
                "battery", "batteries", "napkin", "napkins", "tissue", "tissues", "foil", "freshener", "phenyl",
                "harpic", "dishwash", "lizol", "matchbox", "candle", "candles", "garbage", "trash", "bag", "bags",
                "container", "containers", "bottle", "bottles", "mosquito");
        words(PERSONAL_CARE, "shampoo", "conditioner", "toothpaste", "toothbrush", "deodorant", "deo", "lotion",
                "moisturiser", "moisturizer", "sunscreen", "razor", "trimmer", "perfume", "cologne", "facewash",
                "handwash", "talc", "lipstick", "makeup", "cotton", "earbuds", "pads", "tampons",
                "floss", "mouthwash", "comb", "serum");
    }

    private ShoppingCategories() {
    }

    public static boolean isValid(String key) {
        return key != null && ORDER.contains(key);
    }

    /** The key for any input the client may send — trims, upper-cases — or null when it is not a category. */
    public static String normalize(String key) {
        if (key == null) {
            return null;
        }
        String candidate = key.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return isValid(candidate) ? candidate : null;
    }

    /** Position in the aisle order; unknown keys sort last. */
    public static int orderOf(String key) {
        int index = ORDER.indexOf(key);
        return index < 0 ? ORDER.size() : index;
    }

    /** The best category for an item name — never null; {@link #OTHER} when nothing matches. */
    public static String guess(String name) {
        if (name == null || name.isBlank()) {
            return OTHER;
        }
        List<String> tokens = tokens(name);
        if (tokens.isEmpty()) {
            return OTHER;
        }

        String spaced = " " + String.join(" ", tokens) + " ";
        // Phrases first: the longest one wins, so "frozen peas" beats "peas".
        String best = null;
        int bestLength = 0;
        for (Map.Entry<String, String> phrase : PHRASES.entrySet()) {
            if (phrase.getKey().length() > bestLength && spaced.contains(" " + phrase.getKey() + " ")) {
                best = phrase.getValue();
                bestLength = phrase.getKey().length();
            }
        }
        if (best != null) {
            return best;
        }

        // Head noun (last word) first, then the rest right to left.
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String category = lookup(tokens.get(i));
            if (category != null) {
                return category;
            }
        }
        return OTHER;
    }

    private static String lookup(String token) {
        String direct = WORDS.get(token);
        if (direct != null) {
            return direct;
        }
        // Plurals: "tomatoes" → "tomato", "onions" → "onion", "pastries" → "pastry".
        if (token.endsWith("ies") && token.length() > 4) {
            String singular = WORDS.get(token.substring(0, token.length() - 3) + "y");
            if (singular != null) {
                return singular;
            }
        }
        if (token.endsWith("es") && token.length() > 3) {
            String singular = WORDS.get(token.substring(0, token.length() - 2));
            if (singular != null) {
                return singular;
            }
        }
        if (token.endsWith("s") && token.length() > 3) {
            return WORDS.get(token.substring(0, token.length() - 1));
        }
        return null;
    }

    /** Lower-cased words with digits and punctuation dropped: "2 kg Tomatoes!" → [kg, tomatoes]. */
    private static List<String> tokens(String name) {
        List<String> tokens = new ArrayList<>();
        for (String raw : NON_WORD.split(name.toLowerCase(Locale.ROOT))) {
            if (!raw.isBlank()) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    private static void phrases(String category, String... phrases) {
        for (String phrase : phrases) {
            PHRASES.put(phrase, category);
        }
    }

    private static void words(String category, String... words) {
        for (String word : words) {
            // A multi-word entry here is a phrase; route it so it is matched as one.
            if (word.contains(" ")) {
                PHRASES.put(word, category);
            } else {
                WORDS.putIfAbsent(word, category);
            }
        }
    }
}
