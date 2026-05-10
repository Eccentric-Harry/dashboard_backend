/**
 * Seed script to insert all Strava activity data into MongoDB.
 * 
 * Usage: node backend/scripts/seed_strava_activities.js
 * 
 * Requires: mongodb package (npm install mongodb)
 * Default connection: mongodb://localhost:27017/personal_dashboard
 */

const { MongoClient } = require('mongodb');

const MONGO_URI = process.env.MONGO_URI || 'mongodb+srv://harinathrao13_db_user:oQcgOC69QRDWIVsf@cluster0.5imoofj.mongodb.net/personal_dashboard?retryWrites=true&w=majority&authSource=admin';
const DB_NAME = process.env.DB_NAME || 'personal_dashboard';
const COLLECTION = 'strava_activities';

// Raw CSV data
const rawActivities = [
  { date: "2026-05-10", activityName: "10 May, 5k", sportType: "Run", distanceKm: 5.02, movingTime: "26:32", elevationGainMeters: 0 },
  { date: "2026-05-07", activityName: "Morning Run", sportType: "Run", distanceKm: 1.68, movingTime: "7:53", elevationGainMeters: 0 },
  { date: "2026-04-27", activityName: "27 April, 2.5k", sportType: "Run", distanceKm: 2.50, movingTime: "12:35", elevationGainMeters: 0 },
  { date: "2026-04-26", activityName: "Recovery", sportType: "Run", distanceKm: 1.51, movingTime: "8:25", elevationGainMeters: 0 },
  { date: "2026-04-25", activityName: "25 April, 5k", sportType: "Run", distanceKm: 5.00, movingTime: "26:36", elevationGainMeters: 0 },
  { date: "2026-04-20", activityName: "20 April, 5k", sportType: "Run", distanceKm: 5.00, movingTime: "26:09", elevationGainMeters: 0 },
  { date: "2026-04-18", activityName: "18 April, 2k", sportType: "Run", distanceKm: 2.00, movingTime: "9:24", elevationGainMeters: 0 },
  { date: "2026-04-15", activityName: "15 April, 2k", sportType: "Run", distanceKm: 2.01, movingTime: "9:20", elevationGainMeters: 0 },
  { date: "2026-04-11", activityName: "11 April, 5k", sportType: "Run", distanceKm: 5.00, movingTime: "25:15", elevationGainMeters: 27 },
  { date: "2026-04-02", activityName: "2 April, 5k", sportType: "Run", distanceKm: 5.07, movingTime: "25:57", elevationGainMeters: 46 },
  { date: "2026-03-29", activityName: "Cycling", sportType: "Ride", distanceKm: 10.81, movingTime: "39:13", elevationGainMeters: 64 },
  { date: "2026-03-27", activityName: "27 March, 5k", sportType: "Run", distanceKm: 5.02, movingTime: "26:31", elevationGainMeters: 47 },
  { date: "2026-03-20", activityName: "20 March, 5k", sportType: "Run", distanceKm: 5.01, movingTime: "25:12", elevationGainMeters: 45 },
  { date: "2026-03-18", activityName: "18 March, 5k", sportType: "Run", distanceKm: 5.04, movingTime: "26:25", elevationGainMeters: 48 },
  { date: "2026-03-17", activityName: "17 March, 5k", sportType: "Run", distanceKm: 5.00, movingTime: "26:24", elevationGainMeters: 48 },
  { date: "2026-03-16", activityName: "16 March, 5k", sportType: "Run", distanceKm: 5.00, movingTime: "26:17", elevationGainMeters: 48 },
  { date: "2026-03-14", activityName: "Cycling", sportType: "E-Bike Ride", distanceKm: 44.18, movingTime: "2:21:52", elevationGainMeters: 251 },
  { date: "2026-03-10", activityName: "10 March, 5k", sportType: "Run", distanceKm: 5.00, movingTime: "26:52", elevationGainMeters: 46 },
  { date: "2026-03-09", activityName: "09 March, 10k", sportType: "Run", distanceKm: 10.00, movingTime: "56:48", elevationGainMeters: 89 },
  { date: "2026-03-08", activityName: "08 March, 5k", sportType: "Run", distanceKm: 5.01, movingTime: "26:35", elevationGainMeters: 45 },
  { date: "2026-03-07", activityName: "07 March, 3k", sportType: "Run", distanceKm: 3.00, movingTime: "15:13", elevationGainMeters: 32 },
  { date: "2026-03-05", activityName: "5 March, 2k", sportType: "Run", distanceKm: 2.01, movingTime: "9:44", elevationGainMeters: 15 },
  { date: "2026-03-02", activityName: "02 March, 5k", sportType: "Run", distanceKm: 5.08, movingTime: "26:28", elevationGainMeters: 47 },
  { date: "2026-03-01", activityName: "01 March, 5k", sportType: "Run", distanceKm: 5.01, movingTime: "26:00", elevationGainMeters: 45 },
  { date: "2026-02-26", activityName: "26 Feb, 5k", sportType: "Run", distanceKm: 5.02, movingTime: "27:07", elevationGainMeters: 51 },
  { date: "2026-02-22", activityName: "Hyderabad Heritage Run 5k", sportType: "Run", distanceKm: 4.94, movingTime: "24:11", elevationGainMeters: 26 },
  { date: "2026-02-21", activityName: "Feb 21", sportType: "Run", distanceKm: 1.67, movingTime: "8:02", elevationGainMeters: 18 },
  { date: "2026-02-20", activityName: "20 Feb", sportType: "Run", distanceKm: 1.67, movingTime: "7:58", elevationGainMeters: 18 },
  { date: "2026-02-15", activityName: "5K", sportType: "Run", distanceKm: 5.03, movingTime: "27:42", elevationGainMeters: 44 },
  { date: "2026-02-12", activityName: "12 Feb", sportType: "Run", distanceKm: 1.72, movingTime: "8:37", elevationGainMeters: 16 },
  { date: "2026-02-11", activityName: "11 Feb", sportType: "Run", distanceKm: 1.68, movingTime: "8:31", elevationGainMeters: 18 },
  { date: "2026-02-10", activityName: "10 Feb", sportType: "Run", distanceKm: 1.66, movingTime: "7:53", elevationGainMeters: 17 },
  { date: "2026-02-05", activityName: "05 Feb", sportType: "Run", distanceKm: 1.73, movingTime: "7:58", elevationGainMeters: 18 },
  { date: "2026-02-04", activityName: "04 Feb", sportType: "Run", distanceKm: 1.68, movingTime: "8:07", elevationGainMeters: 16 },
  { date: "2026-02-03", activityName: "Morning Walk", sportType: "Walk", distanceKm: 1.57, movingTime: "18:23", elevationGainMeters: 13 },
  { date: "2026-02-03", activityName: "03 Feb", sportType: "Run", distanceKm: 1.69, movingTime: "8:37", elevationGainMeters: 14 },
  { date: "2026-02-03", activityName: "Morning Walk", sportType: "Walk", distanceKm: 0.95, movingTime: "9:27", elevationGainMeters: 0 },
  { date: "2026-02-02", activityName: "Morning Walk", sportType: "Walk", distanceKm: 0.97, movingTime: "10:34", elevationGainMeters: 10 },
  { date: "2026-02-02", activityName: "02 Feb", sportType: "Run", distanceKm: 1.68, movingTime: "8:31", elevationGainMeters: 18 },
  { date: "2026-02-01", activityName: "Afternoon Walk", sportType: "Walk", distanceKm: 3.19, movingTime: "42:23", elevationGainMeters: 19 },
  { date: "2026-02-01", activityName: "Afternoon Walk", sportType: "Walk", distanceKm: 2.61, movingTime: "27:40", elevationGainMeters: 23 },
  { date: "2026-02-01", activityName: "01 Feb", sportType: "Run", distanceKm: 3.33, movingTime: "17:28", elevationGainMeters: 33 },
  { date: "2026-02-01", activityName: "Afternoon Walk", sportType: "Walk", distanceKm: 1.16, movingTime: "12:27", elevationGainMeters: 0 },
  { date: "2026-01-31", activityName: "31 Jan", sportType: "Walk", distanceKm: 4.06, movingTime: "57:10", elevationGainMeters: 26 },
  { date: "2026-01-30", activityName: "Walk", sportType: "Walk", distanceKm: 1.15, movingTime: "14:33", elevationGainMeters: 3 },
  { date: "2026-01-30", activityName: "30 Jan", sportType: "Run", distanceKm: 1.69, movingTime: "7:59", elevationGainMeters: 17 },
  { date: "2026-01-27", activityName: "27 Jan 02", sportType: "Run", distanceKm: 0.62, movingTime: "2:56", elevationGainMeters: 4 },
  { date: "2026-01-27", activityName: "27 Jan", sportType: "Run", distanceKm: 3.36, movingTime: "17:12", elevationGainMeters: 30 },
  { date: "2026-01-24", activityName: "24 Jan", sportType: "Run", distanceKm: 1.65, movingTime: "7:51", elevationGainMeters: 17 },
  { date: "2026-01-23", activityName: "23 Jan", sportType: "Run", distanceKm: 1.64, movingTime: "8:01", elevationGainMeters: 12 },
  { date: "2026-01-22", activityName: "22 Jan", sportType: "Run", distanceKm: 1.68, movingTime: "8:16", elevationGainMeters: 15 },
  { date: "2026-01-21", activityName: "21 Jan", sportType: "Run", distanceKm: 1.69, movingTime: "8:37", elevationGainMeters: 12 },
  { date: "2026-01-20", activityName: "20 Jan", sportType: "Run", distanceKm: 1.65, movingTime: "8:22", elevationGainMeters: 14 },
  { date: "2026-01-19", activityName: "19 Jan", sportType: "Run", distanceKm: 1.67, movingTime: "8:35", elevationGainMeters: 14 },
  { date: "2026-01-18", activityName: "18 Jan", sportType: "Run", distanceKm: 1.68, movingTime: "8:32", elevationGainMeters: 12 },
  { date: "2026-01-17", activityName: "17 Jan", sportType: "Run", distanceKm: 3.34, movingTime: "17:41", elevationGainMeters: 29 },
  { date: "2026-01-15", activityName: "15 Jan", sportType: "Run", distanceKm: 0.98, movingTime: "4:54", elevationGainMeters: 0 },
  { date: "2026-01-14", activityName: "14 Jan", sportType: "Run", distanceKm: 1.67, movingTime: "8:31", elevationGainMeters: 10 },
  { date: "2026-01-13", activityName: "13 Jan", sportType: "Run", distanceKm: 3.47, movingTime: "18:36", elevationGainMeters: 29 },
  { date: "2026-01-12", activityName: "12 Jan", sportType: "Run", distanceKm: 3.38, movingTime: "18:36", elevationGainMeters: 31 },
  { date: "2026-01-11", activityName: "11 Jan", sportType: "Run", distanceKm: 1.67, movingTime: "8:57", elevationGainMeters: 13 },
  { date: "2025-10-12", activityName: "Day 04 October", sportType: "Run", distanceKm: 2.68, movingTime: "13:05", elevationGainMeters: 23 },
  { date: "2025-10-06", activityName: "Day03 October", sportType: "Run", distanceKm: 3.31, movingTime: "16:00", elevationGainMeters: 28 },
  { date: "2025-10-03", activityName: "Day 02 October", sportType: "Run", distanceKm: 3.26, movingTime: "15:53", elevationGainMeters: 28 },
  { date: "2025-10-02", activityName: "Day 01 October", sportType: "Run", distanceKm: 0.55, movingTime: "2:12", elevationGainMeters: 6 },
  { date: "2025-10-02", activityName: "Day 01 October", sportType: "Run", distanceKm: 3.34, movingTime: "15:41", elevationGainMeters: 28 },
];

/**
 * Parse moving time string to total minutes.
 * Supports "MM:SS" (e.g., "26:32") and "H:MM:SS" (e.g., "2:21:52")
 */
function parseMovingTimeToMinutes(movingTime) {
  if (!movingTime) return 0;
  const parts = movingTime.split(':');
  if (parts.length === 2) {
    return parseInt(parts[0]) + parseInt(parts[1]) / 60;
  } else if (parts.length === 3) {
    return parseInt(parts[0]) * 60 + parseInt(parts[1]) + parseInt(parts[2]) / 60;
  }
  return 0;
}

async function seed() {
  const client = new MongoClient(MONGO_URI);

  try {
    await client.connect();
    console.log('Connected to MongoDB');

    const db = client.db(DB_NAME);
    const collection = db.collection(COLLECTION);

    // Check existing count
    const existingCount = await collection.countDocuments();
    if (existingCount > 0) {
      console.log(`Collection "${COLLECTION}" already has ${existingCount} documents.`);
      const readline = require('readline');
      const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
      
      const answer = await new Promise((resolve) => {
        rl.question('Do you want to drop existing data and re-seed? (y/N): ', resolve);
      });
      rl.close();
      
      if (answer.toLowerCase() !== 'y') {
        console.log('Aborting. No changes made.');
        return;
      }
      
      await collection.deleteMany({});
      console.log('Cleared existing data.');
    }

    // Transform and insert
    const documents = rawActivities.map(a => {
      const movingTimeMinutes = parseMovingTimeToMinutes(a.movingTime);
      const isRunOrWalk = ['Run', 'Walk'].includes(a.sportType);
      const pace = isRunOrWalk && a.distanceKm > 0
        ? Math.round((movingTimeMinutes / a.distanceKm) * 100) / 100
        : null;

      return {
        date: new Date(a.date + 'T00:00:00Z'),
        activityName: a.activityName,
        sportType: a.sportType,
        distanceKm: a.distanceKm,
        movingTime: a.movingTime,
        movingTimeMinutes: Math.round(movingTimeMinutes * 100) / 100,
        elevationGainMeters: a.elevationGainMeters,
        paceMinPerKm: pace,
        stravaEmbedId: null,
        source: 'strava-csv',
        _class: 'com.personal_dashboard.backend.model.StravaActivity'
      };
    });

    const result = await collection.insertMany(documents);
    console.log(`\n✅ Successfully inserted ${result.insertedCount} activities into "${COLLECTION}"`);

    // Print summary
    const sportCounts = {};
    documents.forEach(d => {
      sportCounts[d.sportType] = (sportCounts[d.sportType] || 0) + 1;
    });
    console.log('\nBreakdown by sport type:');
    Object.entries(sportCounts).forEach(([sport, count]) => {
      console.log(`  ${sport}: ${count}`);
    });

    const totalDistance = documents.reduce((sum, d) => sum + d.distanceKm, 0);
    console.log(`\nTotal distance: ${totalDistance.toFixed(2)} km`);

  } catch (error) {
    console.error('Error seeding data:', error);
    process.exit(1);
  } finally {
    await client.close();
    console.log('\nDisconnected from MongoDB');
  }
}

seed();
