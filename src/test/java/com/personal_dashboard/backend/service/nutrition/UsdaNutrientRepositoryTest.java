package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.model.NutrientCacheEntry;
import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Covers ingredient resolution against the embedded USDA table. */
class UsdaNutrientRepositoryTest {

    private UsdaNutrientRepository repo;
    private NutrientCacheRepository cacheRepository;

    @BeforeEach
    void setUp() {
        cacheRepository = mock(NutrientCacheRepository.class);
        when(cacheRepository.findByLookupKey(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        repo = new UsdaNutrientRepository(cacheRepository);
        ReflectionTestUtils.setField(repo, "liveLookupEnabled", false);
        ReflectionTestUtils.setField(repo, "fdcApiKey", "");
        repo.load();
    }

    @Test
    void resolvesTheFullUsdaDescription() {
        var r = repo.resolve("Rice, white, long-grain, regular, enriched, cooked", null, null);

        assertTrue(r.resolved());
        assertEquals("EMBEDDED_EXACT", r.source());
        assertEquals(130.0, r.food().getPer100g().getKcal(), 0.001);
    }

    @Test
    void resolvesColloquialAndRegionalNames() {
        assertTrue(repo.resolve(null, "toor dal", null).resolved());
        assertTrue(repo.resolve(null, "ghee", null).resolved());
        assertTrue(repo.resolve(null, "paneer", null).resolved());
        assertTrue(repo.resolve(null, "chapati", null).resolved());
        assertTrue(repo.resolve(null, "curd", null).resolved());
    }

    @Test
    void matchesLooselyPhrasedDescriptionsToTheRightRecord() {
        var r = repo.resolve("Onions, raw, chopped", "onion", null);

        assertTrue(r.resolved());
        assertEquals(40.0, r.food().getPer100g().getKcal(), 0.001);
    }

    @Test
    void distinguishesCookedFromRawWhereEnergyDensityDiffers() {
        var cooked = repo.resolve("Rice, white, long-grain, regular, enriched, cooked", null, null);
        var flour = repo.resolve("Wheat flour, whole-grain", null, null);

        // Cooked rice absorbs water and is far less energy-dense than dry flour; a resolver
        // that ignored preparation state would conflate the two.
        assertEquals(130.0, cooked.food().getPer100g().getKcal(), 0.001);
        assertEquals(340.0, flour.food().getPer100g().getKcal(), 0.001);
    }

    @Test
    void carriesGlycaemicIndexForCarbohydrateFoods() {
        assertEquals(73, repo.resolve("Rice, white, long-grain, regular, enriched, cooked", null, null)
                .food().getGi());
        assertEquals(28, repo.resolve(null, "chickpeas cooked", null).food().getGi());
    }

    @Test
    void marksRegionalEntriesAsEstimatedRatherThanPassingThemOffAsMeasured() {
        assertTrue(repo.resolve(null, "idli", null).food().isEstimated(),
                "idli is not an FDC record and should be labelled an estimate");
        assertFalse(repo.resolve(null, "white rice cooked", null).food().isEstimated(),
                "cooked white rice is a measured FDC record");
    }

    @Test
    void returnsAMissForUnknownFoodInsteadOfGuessing() {
        var r = repo.resolve("Zzzq unknown substance wibble", "wibble", null);

        assertFalse(r.resolved());
        assertEquals("NO_MATCH", r.source());
        assertNull(r.food());
    }

    @Test
    void negativelyCachesAMissSoItIsNotRetriedEveryMeal() {
        repo.resolve("Zzzq unknown substance wibble", "wibble", null);

        verify(cacheRepository).save(argThat(e ->
                e.isUnresolved() && "UNRESOLVED".equals(e.getSource())));
    }

    @Test
    void servesAPreviouslyCachedLiveLookupWithoutHittingTheApi() {
        UsdaFood cached = UsdaFood.builder()
                .fdcId(999999)
                .description("Some branded snack")
                .aliases(List.of())
                .per100g(NutrientProfile.builder().kcal(480).protein(6).carbs(60).fat(24).build())
                .build();
        when(cacheRepository.findByLookupKey("some branded snack"))
                .thenReturn(Optional.of(NutrientCacheEntry.builder()
                        .lookupKey("some branded snack").food(cached).source("FDC_API").build()));

        var r = repo.resolve("Some branded snack", null, null);

        assertTrue(r.resolved());
        assertEquals("MONGO_CACHE", r.source());
        assertEquals(480.0, r.food().getPer100g().getKcal(), 0.001);
    }

    @Test
    void survivesCacheFailuresWithoutBreakingAnalysis() {
        when(cacheRepository.findByLookupKey(anyString()))
                .thenThrow(new RuntimeException("mongo unavailable"));

        var r = repo.resolve("Rice, white, long-grain, regular, enriched, cooked", null, null);

        assertTrue(r.resolved(), "an embedded hit must not depend on the cache being reachable");
    }

    @Test
    void normalisesPunctuationAndCase() {
        assertEquals("rice white long grain cooked",
                UsdaNutrientRepository.normalize("Rice, white, long-grain, COOKED"));
    }
}
