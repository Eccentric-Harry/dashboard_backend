package com.personal_dashboard.backend.service.nutrition;

import com.personal_dashboard.backend.model.NutrientCacheEntry;
import com.personal_dashboard.backend.repository.NutrientCacheRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private FdcClient fdcClient;

    @BeforeEach
    void setUp() {
        cacheRepository = mock(NutrientCacheRepository.class);
        when(cacheRepository.findByLookupKey(anyString())).thenReturn(Optional.empty());
        when(cacheRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        fdcClient = mock(FdcClient.class);
        when(fdcClient.search(anyString())).thenReturn(Optional.empty());

        repo = new UsdaNutrientRepository(cacheRepository, fdcClient);
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
    void escalatesToFoodDataCentralWhenTheEmbeddedTableMisses() {
        UsdaFood live = UsdaFood.builder()
                .fdcId(174608)
                .description("Seaweed, wakame, raw")
                .aliases(List.of())
                .per100g(NutrientProfile.builder().kcal(45).protein(3.0).carbs(9.14).fat(0.64).build())
                .build();
        when(fdcClient.search(anyString())).thenReturn(Optional.of(live));

        var r = repo.resolve("Seaweed, wakame, raw", "Wakame", null);

        assertTrue(r.resolved());
        assertEquals("FDC_API", r.source());
        assertEquals(45.0, r.food().getPer100g().getKcal(), 0.001);
    }

    @Test
    void cachesALiveResultSoTheQuotaIsSpentOnlyOnce() {
        UsdaFood live = UsdaFood.builder()
                .fdcId(174608).description("Seaweed, wakame, raw").aliases(List.of())
                .per100g(NutrientProfile.builder().kcal(45).protein(3.0).carbs(9.14).fat(0.64).build())
                .build();
        when(fdcClient.search(anyString())).thenReturn(Optional.of(live));

        repo.resolve("Seaweed, wakame, raw", "Wakame", null);

        verify(cacheRepository).save(argThat(e ->
                "FDC_API".equals(e.getSource()) && !e.isUnresolved()
                        && "seaweed wakame raw".equals(e.getLookupKey())));
    }

    @Test
    void doesNotConsultTheApiForAnIngredientTheEmbeddedTableAlreadyKnows() {
        repo.resolve("Rice, white, long-grain, regular, enriched, cooked", "White rice", null);

        verifyNoInteractions(fdcClient);
    }

    @Test
    void resolvesAWholeMealAndPreservesInputOrder() {
        var items = List.of(
                Stage1Extraction.Item.builder().itemId(1)
                        .usdaFoodDescription("Rice, white, long-grain, regular, enriched, cooked")
                        .commonName("White rice").estimatedWeightG(150).build(),
                Stage1Extraction.Item.builder().itemId(2)
                        .usdaFoodDescription("Salt, table").commonName("Salt")
                        .estimatedWeightG(2).build(),
                Stage1Extraction.Item.builder().itemId(3)
                        .usdaFoodDescription("Zzzq unknown wibble").commonName("Mystery")
                        .estimatedWeightG(10).build());

        var results = repo.resolveAll(items);

        assertEquals(3, results.size());
        assertTrue(results.get(0).resolved());
        assertEquals(130.0, results.get(0).food().getPer100g().getKcal(), 0.001);
        assertTrue(results.get(1).resolved());
        assertEquals(38758.0, results.get(1).food().getPer100g().getSodium(), 0.001);
        assertFalse(results.get(2).resolved());
    }

    @Test
    void normalisesPunctuationAndCase() {
        assertEquals("rice white long grain cooked",
                UsdaNutrientRepository.normalize("Rice, white, long-grain, COOKED"));
    }
}
