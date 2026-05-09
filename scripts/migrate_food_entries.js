/**
 * Migration Script: food_entries → daily_food_logs
 *
 * Reads all documents from the `food_entries` collection, groups them by date,
 * restructures them into the new one-document-per-day schema, and writes them
 * to the `daily_food_logs` collection.
 *
 * Usage:
 *   mongosh personal_dashboard migrate_food_entries.js
 *
 * Or if using a connection string:
 *   mongosh "mongodb://localhost:27017/personal_dashboard" migrate_food_entries.js
 *
 * SAFETY: This script writes to a NEW collection (daily_food_logs).
 *         The old food_entries collection is left intact as a backup.
 *         You can drop it manually after verifying the migration.
 */

// Configuration
const SOURCE_COLLECTION = "food_entries";
const TARGET_COLLECTION = "daily_food_logs";
const DB_NAME = db.getName();

print(`\n========================================`);
print(`  Food Entries Migration Script`);
print(`  Database: ${DB_NAME}`);
print(`  Source:   ${SOURCE_COLLECTION}`);
print(`  Target:   ${TARGET_COLLECTION}`);
print(`========================================\n`);

// Step 1: Count source documents
const sourceCount = db.getCollection(SOURCE_COLLECTION).countDocuments();
print(`📊 Found ${sourceCount} documents in '${SOURCE_COLLECTION}'`);

if (sourceCount === 0) {
  print("⚠️  No documents to migrate. Exiting.");
  quit();
}

// Step 2: Run the aggregation pipeline
print(`\n🔄 Running aggregation pipeline...`);

const pipeline = [
  // Stage 1: Project fields and extract the date string
  {
    $project: {
      _id: 0,
      dateStr: { $dateToString: { format: "%Y-%m-%d", date: "$date" } },
      date: "$date",
      description: "$description",
      calories: { $ifNull: ["$calories", 0] },
      proteinGrams: { $ifNull: ["$proteinGrams", 0] },
      mealType: { $ifNull: ["$mealType", "Snack"] },
      mealQuality: "$mealQuality",
      notes: "$notes",
      recipeCategory: "$recipeCategory",
      serving: "$serving",
      servingNotes: "$servingNotes",
      sourceNotes: "$sourceNotes",
      importKey: "$importKey",
    },
  },

  // Stage 2: Group by date string
  {
    $group: {
      _id: "$dateStr",
      date: { $first: "$date" },
      entries: {
        $push: {
          id: { $toString: new ObjectId() },
          description: "$description",
          calories: "$calories",
          proteinGrams: "$proteinGrams",
          mealType: "$mealType",
          mealQuality: "$mealQuality",
          notes: "$notes",
          recipeCategory: "$recipeCategory",
          serving: "$serving",
          servingNotes: "$servingNotes",
          sourceNotes: "$sourceNotes",
          importKey: "$importKey",
          timestamp: { $toDate: "$date" },
        },
      },
      totalCalories: { $sum: { $ifNull: ["$calories", 0] } },
      totalProteinGrams: { $sum: { $ifNull: ["$proteinGrams", 0] } },
    },
  },

  // Stage 3: Restructure into the desired schema
  {
    $project: {
      _id: "$_id",
      mealId: "$_id",
      date: "$date",
      dailyTotals: {
        totalCalories: "$totalCalories",
        totalProteinGrams: "$totalProteinGrams",
      },
      entries: 1,
    },
  },

  // Stage 4: Merge into target collection (upsert by _id)
  {
    $merge: {
      into: TARGET_COLLECTION,
      on: "_id",
      whenMatched: "replace",
      whenNotMatched: "insert",
    },
  },
];

// Run the basic aggregation first (without the meals grouping — we'll do that in a second pass)
// because MongoDB aggregation can't easily create dynamic keys in a map from $group

// Instead, let's do it programmatically for correctness:
print(`\n🔧 Processing documents programmatically for correct meal grouping...\n`);

const cursor = db.getCollection(SOURCE_COLLECTION).find({}).sort({ date: 1 });
const dailyLogs = {};

let processedCount = 0;
cursor.forEach((doc) => {
  // Extract the date string (YYYY-MM-DD)
  let dateStr;
  if (doc.date instanceof Date) {
    // Convert to IST-aware date string
    const d = doc.date;
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, "0");
    const day = String(d.getDate()).padStart(2, "0");
    dateStr = `${year}-${month}-${day}`;
  } else {
    dateStr = String(doc.date).slice(0, 10);
  }

  const mealType = doc.mealType || "Snack";

  // Initialize daily log if needed
  if (!dailyLogs[dateStr]) {
    dailyLogs[dateStr] = {
      _id: dateStr,
      mealId: dateStr,
      date: doc.date,
      dailyTotals: { totalCalories: 0, totalProteinGrams: 0 },
      meals: {},
      _class: "com.personal_dashboard.backend.model.DailyFoodLog",
    };
  }

  const log = dailyLogs[dateStr];

  // Initialize meal type array if needed
  if (!log.meals[mealType]) {
    log.meals[mealType] = [];
  }

  // Create meal entry
  const entry = {
    id: new ObjectId().toString(),
    description: doc.description || null,
    calories: doc.calories || 0,
    proteinGrams: doc.proteinGrams || 0,
    mealQuality: doc.mealQuality || null,
    notes: doc.notes || null,
    recipeCategory: doc.recipeCategory || null,
    serving: doc.serving || null,
    servingNotes: doc.servingNotes || null,
    sourceNotes: doc.sourceNotes || null,
    importKey: doc.importKey || null,
    timestamp: doc.date || new Date(),
  };

  log.meals[mealType].push(entry);

  // Update daily totals
  log.dailyTotals.totalCalories += doc.calories || 0;
  log.dailyTotals.totalProteinGrams += doc.proteinGrams || 0;

  processedCount++;
});

print(`✅ Processed ${processedCount} entries into ${Object.keys(dailyLogs).length} daily documents\n`);

// Step 3: Write to target collection
print(`💾 Writing to '${TARGET_COLLECTION}'...\n`);

let insertedCount = 0;
let updatedCount = 0;

for (const [dateStr, dailyLog] of Object.entries(dailyLogs)) {
  const existing = db.getCollection(TARGET_COLLECTION).findOne({ _id: dateStr });
  if (existing) {
    db.getCollection(TARGET_COLLECTION).replaceOne({ _id: dateStr }, dailyLog);
    updatedCount++;
  } else {
    db.getCollection(TARGET_COLLECTION).insertOne(dailyLog);
    insertedCount++;
  }
}

print(`\n========================================`);
print(`  Migration Complete!`);
print(`========================================`);
print(`  📥 Source documents:  ${sourceCount}`);
print(`  📤 Daily logs created: ${insertedCount}`);
print(`  🔄 Daily logs updated: ${updatedCount}`);
print(`  📊 Total daily logs:   ${Object.keys(dailyLogs).length}`);
print(`\n  ⚠️  The old '${SOURCE_COLLECTION}' collection is still intact.`);
print(`  ⚠️  Drop it manually after verifying: db.${SOURCE_COLLECTION}.drop()`);
print(`========================================\n`);

// Step 4: Verify
print(`🔍 Verification — sample document:\n`);
const sample = db.getCollection(TARGET_COLLECTION).findOne();
if (sample) {
  printjson(sample);
}
