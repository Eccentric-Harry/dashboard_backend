# Nutrition eval fixture

Ground-truth benchmark for the two-stage meal-analysis pipeline
(`NutritionPipelineService`), used by `NutritionEvalHarness`.

## Setup

```bash
python3 build_fixture.py          # downloads manifest + 60 images (~24 MB)
```

`manifest.json` is committed; `images/` is gitignored and regenerated on demand.
Only stdlib Python is needed.

## Running

The harness makes **2 LLM calls per dish** and costs real money, so it never runs under a
plain `./mvnw test`. It is gated on `NUTRITION_EVAL=1`.

```bash
# Cheap smoke test: 12 dishes, 24 calls
NUTRITION_EVAL=1 EVAL_LIMIT=12 EVAL_PROVIDER=claude ./mvnw test -Dtest=NutritionEvalHarness
```

```bash
# The A/B that decides whether Gemini should stay primary
NUTRITION_EVAL=1 EVAL_LIMIT=20 EVAL_PROVIDER=gemini ./mvnw test -Dtest=NutritionEvalHarness
```

```bash
# Full 60-dish run
NUTRITION_EVAL=1 ./mvnw test -Dtest=NutritionEvalHarness
```

| Env var | Default | Purpose |
|---|---|---|
| `NUTRITION_EVAL` | *(unset)* | Must be `1` or the harness is skipped |
| `EVAL_PROVIDER` | `claude` | `claude` \| `gemini` — used for **both** stages |
| `EVAL_LIMIT` | all | Cap the dish count |
| `ANTHROPIC_MODEL` | `claude-sonnet-5` | Model under test |
| `GEMINI_MODEL` | `gemini-3.6-flash` | Model under test |

API keys come from `ANTHROPIC_API_KEY` / `GEMINI_API_KEY`, same as the app.

## What it reports

- **MedAPE / MAPE / MAE** for mass, energy, protein, carbs, fat.
  Read **MedAPE** as the headline — the fixture deliberately spans 11 g to 841 g, and
  percentage error on a 2 kcal dish is noise. Dishes under 50 kcal are excluded from
  percentage metrics but kept in MAE.
- **Portion bias slope** — signed relative error regressed on true mass. Published work
  finds systematic underestimation that *grows* with portion size (slope −0.23 to −0.50).
  If that reproduces here it is correctable with a calibration factor in Java.
- **Identification recall** — fraction of ground-truth ingredients the model named.
- **Math gate** — how often the model's self-reported `gate_passed` actually holds under
  Atwater. A gap here is the evidence for enforcing the gate in Java.

Reference points for energy MAPE: **~36%** for GPT-4o / Claude 3.5 Sonnet, **64–110%** for
Gemini 1.5 Pro (see `design/NUTRITION_ACCURACY_PLAN.md` §2.1).

## Known limitations — read before trusting a number

1. **Wrong cuisine.** Nutrition5k is Google cafeteria food. Of 554 ingredients, ~2 match
   any Indian term; the vocabulary is bacon, steak, caesar salad, scrambled eggs. It
   validates the pipeline's machinery, identification, and portion estimation. It does
   **not** validate the South Indian portion ladder or IFCT matching.
2. **Not vegetarian.** Many dishes contain meat. Fine for measuring accuracy — ground
   truth is ground truth — but do not reuse these images anywhere user-facing.
3. **640×480 images.** Well below the 2576 px that Sonnet 5 / Opus 4.8 accept, so results
   here likely *understate* real-world accuracy on a modern phone photo. Treat the numbers
   as a floor and a comparison tool, not an absolute.
4. **Single overhead angle.** Cannot measure the multi-angle improvement from plan §3.3.

A personal eval set of ~20–25 weighed South Indian meals is still needed to cover 1, 2,
and 4. This fixture makes that set smaller and later, not unnecessary.

## Attribution

Nutrition5k — Thames et al., *Nutrition5k: Towards Automatic Nutritional Understanding of
Generic Food*, CVPR 2021. Google Research. Licensed **CC BY 4.0**.
<https://github.com/google-research-datasets/Nutrition5k>
