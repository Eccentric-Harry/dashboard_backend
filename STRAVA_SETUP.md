# Strava API Integration Setup

## Overview

This document explains how to set up and use the Strava API integration for the workouts section of the personal dashboard.

## Prerequisites

1. **Strava Account**: You need a Strava account
2. **Strava API Application**: Register an application at https://www.strava.com/settings/api
3. **Access Token**: Obtain an access token from the Strava API dashboard

## Configuration

### Step 1: Get Your Strava API Credentials

1. Go to https://www.strava.com/settings/api
2. Fill in the application details:
   - **Application Name**: Personal Dashboard
   - **Category**: Visualizer
   - **Website**: (optional, or use your domain)
   - **Application Description**: Dashboard to track my workouts and activities
   - **Authorization Callback Domain**: localhost (for local development)

3. You'll get:
   - **Client ID**: Used for OAuth flow (if implementing later)
   - **Client Secret**: Keep this secret!
   - **Access Token**: Used for API authentication (shown on the settings page)
   - **Refresh Token**: For refreshing the access token

### Step 2: Configure Environment Variables

Set the following environment variable with your Strava access token:

```bash
export STRAVA_ACCESS_TOKEN="your_access_token_here"
```

For macOS/Linux, add to your shell profile (~/.zshrc, ~/.bash_profile):
```bash
export STRAVA_ACCESS_TOKEN="your_access_token_from_strava"
```

Then reload: `source ~/.zshrc`

For Windows, set via System Environment Variables.

### Step 3: Access the Endpoints

The backend provides the following workouts endpoints:

#### 1. Get All Workouts Data
```bash
curl -X GET http://localhost:8080/api/v1/workouts
```

Response includes:
- Athlete profile information
- Activity statistics (runs, rides, swims)
- 20 most recent activities

#### 2. Get Recent Activities
```bash
curl -X GET http://localhost:8080/api/v1/workouts/recent?limit=20
```

#### 3. Get All Activities
```bash
curl -X GET http://localhost:8080/api/v1/workouts/all
```

This fetches all activities with automatic pagination (up to 10 pages).

## Data Mapping

### Athlete Profile
```json
{
  "id": "strava_athlete_id",
  "name": "First Last",
  "location": "City, State",
  "profileImage": "url_to_image",
  "premium": true/false,
  "follower_count": 42
}
```

### Activity Statistics
- **Recent Activities**: Last 4 weeks
- **YTD**: Year-to-date (since Jan 1)
- **All**: All-time totals
- Includes: runs, rides, swims with distance, time, elevation, achievements

### Activity Summary
Each activity includes:
- ID, name, sport type (Run, Ride, etc.)
- Distance (meters, converted to km in response)
- Moving time (seconds, converted to minutes in response)
- Elevation gain, start date, average pace
- Heart rate data (if available)
- Kudos count

## API Rate Limits

Strava API has rate limits:
- **Overall Rate Limit**: 200 requests every 15 minutes, 2,000 daily
- **Read Rate Limit**: 100 requests every 15 minutes, 1,000 daily

The backend implementation:
- Fetches up to 10 pages max for all activities to avoid excessive API calls
- Caches results in memory (no persistence yet)
- Logs all requests for monitoring

## Token Expiration

The access token shown in the Strava API dashboard lasts a long time. However, for a production OAuth flow:
- Access tokens expire after 6 hours
- Use the refresh token to get a new access token
- (Future implementation: automatic token refresh)

## Security Notes

⚠️ **Important**:
- Never commit your access token to version control
- Use environment variables or secure vaults
- The `Client Secret` should never be used in frontend code
- For production OAuth flow, implement server-side token exchange

## Troubleshooting

### 401 Unauthorized
- Check that `STRAVA_ACCESS_TOKEN` environment variable is set correctly
- Verify the token hasn't expired
- Generate a new token from https://www.strava.com/settings/api

### 429 Rate Limited
- You've exceeded the API rate limit
- Wait before making new requests
- Consider implementing caching

### 500 Internal Server Error
- Check the server logs for detailed error messages
- Verify Strava API is accessible (https://www.strava.com/api/v3 should be reachable)
- Ensure MongoDB is running if implementing persistence

## Next Steps

1. **Frontend Integration**: Create React components to display workout data
2. **Persistence**: Store activity data in MongoDB for historical analysis
3. **OAuth Flow**: Implement proper OAuth to support multiple athletes
4. **Webhooks**: Listen to Strava webhooks for real-time activity updates
5. **Advanced Metrics**: Calculate custom metrics and insights

## References

- [Strava API Documentation](https://developers.strava.com/docs/)
- [Strava API Settings](https://www.strava.com/settings/api)
- [API Rate Limits](https://developers.strava.com/docs/#rate-limiting)
