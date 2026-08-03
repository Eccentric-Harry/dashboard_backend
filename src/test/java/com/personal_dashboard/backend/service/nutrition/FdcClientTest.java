package com.personal_dashboard.backend.service.nutrition;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Covers the live FoodData Central path, especially its refusals.
 *
 * <p>The rejections matter more than the acceptances. FDC's relevance ranking will happily
 * return a branded dessert for "salt", and a nutrient database that accepts the top hit
 * unconditionally is less trustworthy than the model it replaced.</p>
 */
class FdcClientTest {

    private FdcClient client;
    private FdcRateLimiter limiter;
    private MockRestServiceServer server;

    private static final String ENDPOINT = "https://api.nal.usda.gov/fdc/v1/foods/search";

    @BeforeEach
    void setUp() {
        limiter = new FdcRateLimiter();
        ReflectionTestUtils.setField(limiter, "hourlyBudget", 800);

        client = new FdcClient(limiter);
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        ReflectionTestUtils.setField(client, "searchEndpoint", ENDPOINT);
        ReflectionTestUtils.setField(client, "enabled", true);
        ReflectionTestUtils.setField(client, "connectTimeoutMs", 1000);
        ReflectionTestUtils.setField(client, "readTimeoutMs", 2000);
        ReflectionTestUtils.setField(client, "includeBranded", false);
        client.init();

        RestTemplate rt = (RestTemplate) ReflectionTestUtils.getField(client, "restTemplate");
        server = MockRestServiceServer.createServer(rt);
    }

    /** Builds a foods/search response with a single food. */
    private static String food(String description, String dataType, int fdcId,
                               double kcal, double protein, double carbs, double fat) {
        return String.format("""
                { "foods": [ {
                    "fdcId": %d, "description": "%s", "dataType": "%s",
                    "foodNutrients": [
                      {"nutrientNumber":"208","nutrientName":"Energy","unitName":"KCAL","value":%s},
                      {"nutrientNumber":"203","nutrientName":"Protein","unitName":"G","value":%s},
                      {"nutrientNumber":"205","nutrientName":"Carbohydrate","unitName":"G","value":%s},
                      {"nutrientNumber":"204","nutrientName":"Total lipid","unitName":"G","value":%s},
                      {"nutrientNumber":"307","nutrientName":"Sodium","unitName":"MG","value":5}
                    ] } ] }
                """, fdcId, description, dataType, kcal, protein, carbs, fat);
    }

    @Test
    void sendsKeyAsAHeaderAndDataTypeAsAJsonArray() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                // Key in a header rather than the URL keeps it out of access logs.
                .andExpect(header("X-Api-Key", "test-key"))
                .andExpect(jsonPath("$.query").value("cheddar cheese"))
                .andExpect(jsonPath("$.dataType").isArray())
                .andExpect(jsonPath("$.dataType[0]").value("Foundation"))
                .andRespond(withSuccess(
                        food("Cheese, cheddar", "SR Legacy", 170899, 403, 22.87, 3.09, 33.31),
                        MediaType.APPLICATION_JSON));

        assertTrue(client.search("cheddar cheese").isPresent());
        server.verify();
    }

    @Test
    void excludesBrandedByDefault() {
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.dataType[?(@ == 'Branded')]").doesNotExist())
                .andRespond(withSuccess(
                        food("Cheese, cheddar", "SR Legacy", 170899, 403, 22.87, 3.09, 33.31),
                        MediaType.APPLICATION_JSON));

        client.search("cheddar cheese");
        server.verify();
    }

    @Test
    void parsesNutrientsPer100g() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                food("Cheese, cheddar", "SR Legacy", 170899, 403, 22.87, 3.09, 33.31),
                MediaType.APPLICATION_JSON));

        UsdaFood f = client.search("cheddar cheese").orElseThrow();

        assertEquals(170899, f.getFdcId());
        assertEquals(403.0, f.getPer100g().getKcal(), 0.001);
        assertEquals(22.87, f.getPer100g().getProtein(), 0.001);
        assertFalse(f.isEstimated(), "an FDC record is measured, not estimated");
    }

    @Test
    void prefersFoundationOverLowerQualityDatasets() {
        String body = """
                { "foods": [
                  { "fdcId": 1, "description": "Rice, white, cooked", "dataType": "Survey (FNDDS)",
                    "foodNutrients": [
                      {"nutrientNumber":"208","unitName":"KCAL","value":140},
                      {"nutrientNumber":"203","unitName":"G","value":2.7},
                      {"nutrientNumber":"205","unitName":"G","value":30},
                      {"nutrientNumber":"204","unitName":"G","value":0.3} ] },
                  { "fdcId": 2, "description": "Rice, white, cooked", "dataType": "Foundation",
                    "foodNutrients": [
                      {"nutrientNumber":"208","unitName":"KCAL","value":130},
                      {"nutrientNumber":"203","unitName":"G","value":2.69},
                      {"nutrientNumber":"205","unitName":"G","value":28.17},
                      {"nutrientNumber":"204","unitName":"G","value":0.28} ] } ] }
                """;
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        UsdaFood f = client.search("Rice, white, cooked").orElseThrow();

        assertEquals(2, f.getFdcId(), "the Foundation record should win despite being listed second");
        assertEquals(130.0, f.getPer100g().getKcal(), 0.001);
    }

    @Test
    void rejectsAMatchThatSharesTooLittleWithTheQuery() {
        // The classic failure: searching "salt" and being handed a dessert.
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                food("Ice cream, salted caramel, premium", "Branded", 55555, 260, 4, 30, 14),
                MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), client.search("Salt, table"));
    }

    @Test
    void rejectsImplausibleEnergyDensity() {
        // A per-serving value mistaken for per-100 g shows up as an impossible density.
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                food("Cheese, cheddar", "SR Legacy", 170899, 4030, 22.87, 3.09, 33.31),
                MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), client.search("cheddar cheese"));
    }

    @Test
    void rejectsRecordsWhoseEnergyContradictsTheirMacros() {
        // 500 kcal cannot come from 2 g protein, 3 g carbs and 1 g fat.
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                food("Cheese, cheddar", "SR Legacy", 170899, 500, 2, 3, 1),
                MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), client.search("cheddar cheese"));
    }

    @Test
    void acceptsZeroMacroFoodsThatAlsoHaveNoEnergy() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(
                food("Salt, table", "SR Legacy", 173468, 0, 0, 0, 0),
                MediaType.APPLICATION_JSON));

        assertTrue(client.search("Salt, table").isPresent(),
                "salt legitimately has no macros and no energy");
    }

    @Test
    void ignoresTheKilojouleEnergyRow() {
        String body = """
                { "foods": [ { "fdcId": 9, "description": "Cheese, cheddar", "dataType": "SR Legacy",
                    "foodNutrients": [
                      {"nutrientNumber":"208","unitName":"kJ","value":1687},
                      {"nutrientNumber":"208","unitName":"KCAL","value":403},
                      {"nutrientNumber":"203","unitName":"G","value":22.87},
                      {"nutrientNumber":"205","unitName":"G","value":3.09},
                      {"nutrientNumber":"204","unitName":"G","value":33.31} ] } ] }
                """;
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertEquals(403.0, client.search("cheddar cheese").orElseThrow().getPer100g().getKcal(), 0.001);
    }

    @Test
    void tracksRemainingQuotaFromTheResponseHeader() {
        server.expect(requestTo(ENDPOINT)).andRespond(
                withSuccess(food("Cheese, cheddar", "SR Legacy", 170899, 403, 22.87, 3.09, 33.31),
                        MediaType.APPLICATION_JSON)
                        .header("X-RateLimit-Remaining", "42"));

        client.search("cheddar cheese");

        assertEquals(42, limiter.getServerRemaining());
    }

    @Test
    void stopsCallingAfterA429() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertEquals(Optional.empty(), client.search("cheddar cheese"));
        assertTrue(limiter.isExhausted(), "a 429 must pause further lookups");

        // No second request is issued, so the strict mock server stays satisfied.
        assertEquals(Optional.empty(), client.search("something else"));
        server.verify();
    }

    @Test
    void returnsEmptyAndMakesNoCallWhenNoKeyIsConfigured() {
        ReflectionTestUtils.setField(client, "apiKey", "");

        assertFalse(client.isConfigured());
        assertEquals(Optional.empty(), client.search("cheddar cheese"));
        server.verify();
    }

    @Test
    void survivesAServerErrorWithoutThrowing() {
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());

        assertEquals(Optional.empty(), client.search("cheddar cheese"),
                "an FDC outage must degrade to an unmatched ingredient, not a failed analysis");
    }
}
