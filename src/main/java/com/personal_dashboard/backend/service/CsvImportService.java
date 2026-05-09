package com.personal_dashboard.backend.service;

import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.personal_dashboard.backend.model.MealEntry;
import com.personal_dashboard.backend.model.Transaction;
import com.personal_dashboard.backend.repository.TransactionRepository;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
@AllArgsConstructor
public class CsvImportService {

    private final DailyFoodLogService dailyFoodLogService;
    private final TransactionRepository transactionRepository;

    private static final String IST_TIMEZONE = "Asia/Kolkata";
    private static final ZoneId IST = ZoneId.of(IST_TIMEZONE);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, yyyy");

    public Map<String, Object> importCsvFiles(MultipartFile foodCsv, MultipartFile spendingCsv) throws Exception {
        Map<String, Object> result = new HashMap<>();

        if (foodCsv != null && !foodCsv.isEmpty()) {
            FoodImportResult foodImport = parseFoodCsv(foodCsv);
            result.put("foodEntriesImported", foodImport.importedCount());
            result.put("foodEntriesSkippedWithoutDate", foodImport.skippedWithoutDate());
            result.put("foodEntriesSkippedDuplicates", foodImport.skippedDuplicates());
            log.info("Imported {} food entries", foodImport.importedCount());
        }

        if (spendingCsv != null && !spendingCsv.isEmpty()) {
            List<Transaction> transactions = parseSpendingCsv(spendingCsv);
            transactionRepository.saveAll(transactions);
            result.put("transactionsImported", transactions.size());
            log.info("Imported {} transactions", transactions.size());
        }

        return result;
    }

    private FoodImportResult parseFoodCsv(MultipartFile file) throws Exception {
        int importedCount = 0;
        int skippedWithoutDate = 0;
        int skippedDuplicates = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
             CSVReader csvReader = new CSVReaderBuilder(reader)
                     .withCSVParser(new CSVParserBuilder().withSeparator(',').build())
                     .build()) {

            String[] header = csvReader.readNext();
            Map<String, Integer> headerIndex = buildHeaderIndex(header);
            String[] line;
            while ((line = csvReader.readNext()) != null) {
                try {
                    String dateStr = getValue(line, headerIndex, "Date");
                    if (dateStr.isBlank()) {
                        skippedWithoutDate++;
                        continue;
                    }

                    ParsedFoodLine parsed = parseFoodLine(line, headerIndex);
                    if (parsed == null) {
                        continue;
                    }

                    // Check for duplicate by importKey
                    if (parsed.entry().getImportKey() != null &&
                            dailyFoodLogService.existsByImportKey(parsed.dateStr(), parsed.entry().getImportKey())) {
                        skippedDuplicates++;
                        continue;
                    }

                    // Add meal via service (creates or appends to daily document)
                    dailyFoodLogService.addMeal(parsed.dateStr(), parsed.mealType(), parsed.entry());
                    importedCount++;
                } catch (Exception e) {
                    log.warn("Skipping invalid food entry: {}", Arrays.toString(line), e);
                    continue;
                }
            }
        }

        return new FoodImportResult(importedCount, skippedWithoutDate, skippedDuplicates);
    }

    private ParsedFoodLine parseFoodLine(String[] line, Map<String, Integer> headerIndex) {
        if (line.length == 0 || headerIndex.isEmpty()) {
            return null;
        }

        String description = getValue(line, headerIndex, "Food Item", "Description");
        String caloriesStr = getValue(line, headerIndex, "Calories(kcal)", "Calories", "Calories (kcal)");
        String dateStr = getValue(line, headerIndex, "Date");
        String mealType = getValue(line, headerIndex, "Meal", "Meal Type");
        String proteinStr = getValue(line, headerIndex, "Protein(g)", "Protein", "Protein (g)");
        String mealQuality = getValue(line, headerIndex, "Meal Quality");
        String notes = getValue(line, headerIndex, "Notes");
        String recipeCategory = getValue(line, headerIndex, "Recipe Category");
        String serving = getValue(line, headerIndex, "Serving");
        String servingNotes = getValue(line, headerIndex, "Serving_Notes", "Serving Notes");
        String sourceNotes = getValue(line, headerIndex, "Source / Notes", "Source Notes");

        if (description.isEmpty() || dateStr.isEmpty() || mealType.isEmpty()) {
            return null;
        }

        try {
            LocalDate date = parseDate(dateStr);
            Integer calories = parseInteger(caloriesStr);
            Integer protein = parseInteger(proteinStr);

            // Estimate calories from protein if missing
            if (calories == null || calories == 0) {
                calories = protein != null ? protein * 4 : 0;
            }

            // Normalize meal type
            mealType = normalizeMealType(mealType);

            String isoDateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);

            MealEntry entry = MealEntry.builder()
                    .description(description)
                    .calories(calories)
                    .proteinGrams(protein)
                    .mealQuality(emptyToNull(mealQuality))
                    .notes(emptyToNull(notes))
                    .recipeCategory(emptyToNull(recipeCategory))
                    .serving(emptyToNull(serving))
                    .servingNotes(emptyToNull(servingNotes))
                    .sourceNotes(emptyToNull(sourceNotes))
                    .importKey(buildImportKey(description, date, mealType, calories, protein, serving, notes, sourceNotes))
                    .timestamp(Instant.now())
                    .build();

            return new ParsedFoodLine(isoDateStr, mealType, entry);
        } catch (Exception e) {
            log.debug("Failed to parse food line: {}", Arrays.toString(line), e);
            return null;
        }
    }

    private List<Transaction> parseSpendingCsv(MultipartFile file) throws Exception {
        List<Transaction> entries = new ArrayList<>();
        Random random = new Random(42); // Seed for reproducibility

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream()));
             CSVReader csvReader = new CSVReaderBuilder(reader)
                     .withCSVParser(new CSVParserBuilder().withSeparator(',').build())
                     .withSkipLines(1)
                     .build()) {

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                try {
                    Transaction transaction = parseSpendingLine(line, random);
                    if (transaction != null) {
                        entries.add(transaction);
                    }
                } catch (Exception e) {
                    log.warn("Skipping invalid spending entry: {}", Arrays.toString(line), e);
                    continue;
                }
            }
        }

        return entries;
    }

    private Transaction parseSpendingLine(String[] line, Random random) {
        if (line.length < 4) {
            return null;
        }

        String description = sanitize(line[0]);
        String amountStr = sanitize(line[1]);
        String category = sanitize(line[2]);
        String dateStr = sanitize(line[3]);

        if (description.isEmpty() || amountStr.isEmpty() || category.isEmpty() || dateStr.isEmpty()) {
            return null;
        }

        try {
            BigDecimal amount = parseAmount(amountStr);
            LocalDate date = parseDate(dateStr);

            // Generate random time between 09:00 and 18:00 IST
            int hour = 9 + random.nextInt(10);
            int minute = random.nextInt(60);
            LocalDateTime dateTime = date.atTime(hour, minute);
            Instant instant = dateTime.atZone(IST).toInstant();

            return Transaction.builder()
                    .description(description)
                    .amount(amount)
                    .category(category)
                    .type("Expense") // Default to Expense
                    .date(instant)
                    .build();
        } catch (Exception e) {
            log.debug("Failed to parse spending line: {}", Arrays.toString(line), e);
            return null;
        }
    }

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            log.debug("Failed to parse date: {}", dateStr, e);
            return LocalDate.now();
        }
    }

    private BigDecimal parseAmount(String amountStr) {
        // Remove currency symbol and commas
        String cleaned = amountStr.replaceAll("[₹,\\s]", "").trim();
        if (cleaned.isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            log.debug("Failed to parse amount: {}", amountStr, e);
            return BigDecimal.ZERO;
        }
    }

    private Integer parseInteger(String str) {
        String cleaned = str.replaceAll("[^0-9.]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(cleaned));
        } catch (Exception e) {
            log.debug("Failed to parse integer: {}", str, e);
            return null;
        }
    }

    private String sanitize(String str) {
        return str == null ? "" : str.replace("\uFEFF", "").trim().replaceAll("^\"|\"$", "");
    }

    private String emptyToNull(String str) {
        return str == null || str.isBlank() ? null : str;
    }

    private Map<String, Integer> buildHeaderIndex(String[] header) {
        Map<String, Integer> headerIndex = new HashMap<>();
        if (header == null) {
            return headerIndex;
        }

        for (int index = 0; index < header.length; index++) {
            String key = normalizeHeader(header[index]);
            if (!key.isEmpty()) {
                headerIndex.put(key, index);
            }
        }

        return headerIndex;
    }

    private String getValue(String[] line, Map<String, Integer> headerIndex, String... possibleHeaders) {
        for (String header : possibleHeaders) {
            Integer index = headerIndex.get(normalizeHeader(header));
            if (index != null && index < line.length) {
                return sanitize(line[index]);
            }
        }

        return "";
    }

    private String normalizeHeader(String header) {
        return sanitize(header).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String buildImportKey(String description, LocalDate date, String mealType, Integer calories, Integer protein,
                                  String serving, String notes, String sourceNotes) {
        String keySource = String.join("|",
                "food-log",
                date.toString(),
                description.toLowerCase(Locale.ROOT),
                mealType.toLowerCase(Locale.ROOT),
                String.valueOf(calories),
                String.valueOf(protein),
                serving.toLowerCase(Locale.ROOT),
                notes.toLowerCase(Locale.ROOT),
                sourceNotes.toLowerCase(Locale.ROOT));

        return UUID.nameUUIDFromBytes(keySource.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String normalizeMealType(String mealType) {
        String normalized = mealType.toLowerCase().trim();

        if (normalized.equals("breakfast")) return "Breakfast";
        if (normalized.equals("lunch")) return "Lunch";
        if (normalized.equals("dinner")) return "Dinner";
        if (normalized.equals("snack")) return "Snack";
        if (normalized.equals("midnight") || normalized.equals("mid night")) return "Midnight";
        if (normalized.contains("post workout") || normalized.contains("post_workout")) return "Post Workout";
        if (normalized.equals("mid-morning") || normalized.equals("midmorning")) return "Mid-Morning";

        return "Snack"; // Default fallback
    }

    private record FoodImportResult(int importedCount, int skippedWithoutDate, int skippedDuplicates) {
    }

    private record ParsedFoodLine(String dateStr, String mealType, MealEntry entry) {
    }
}
