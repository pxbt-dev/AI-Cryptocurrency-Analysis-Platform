# aiCryptoPredictor

Spring Boot 3.1 / Java 21 app behind www.aicryptopredictor.com. It pulls Binance market data, trains Weka models per symbol and timeframe, and serves a Chart.js dashboard (`templates/chart.html`) plus a JSON API. Deployed on Railway from `master`.

## Build and test

```bash
mvn -B verify              # offline unit + Spring tests, JaCoCo report in target/site/jacoco
mvn -B verify -Pbacktest   # live-data backtest (BacktestRunner, @Tag("backtest")); slow, needs Binance
```

CI (`.github/workflows/ci.yml`) runs `mvn -B verify` on every PR. It must stay green.

UI smoke tests (Playwright, `ui-tests/`) run against a deployed site, not in the Maven build:

```bash
cd ui-tests && npm ci && npx playwright install chromium
BASE_URL=https://www.aicryptopredictor.com npx playwright test
```

`.github/workflows/ui-smoke.yml` runs them automatically against each PR's Railway preview and posts a "UI smoke" status on the PR. Keep them read-only (page loads and GET requests only).

## Layout

- `Gateway/BinanceGateway` – raw Binance REST klines (WebClient)
- `service/` – data (`BinanceHistoricalService`, `MarketDataService`, `RealTimeDataService` for WebSockets), ML (`AIModelService`, `TrainingDataService`), prediction (`PricePredictionService`), backtest (`BacktestService`), external signals (`FearGreedService`, `FundingRateService`)
- `controller/` – JSON API under `/api/**`, HTML via `HtmlController`
- `config/` – `SchedulingConfig`, `AdminEndpointFilter`, WebClient, metrics, symbols
- `util/FeatureExtractor`, `util/Ta4jConverter` – indicators and ML features
- Symbols come from `app.symbols` (BTC, SOL, TAO, WIF)

## House rules

- **No live network calls in tests.** Use `@ActiveProfiles("test")` for Spring tests; it disables training, WebSockets, startup preload/backtest and `@Scheduled` jobs, and points external URLs at a closed port. Stub HTTP with WireMock (see `ExternalApiClientsTest`). Live-data tests must be tagged `backtest`.
- **Every bug fix gets a test** that fails before the fix.
- Never assert on real market prices or ML accuracy; use fixed fixtures.
- New startup or background work must respect `app.background.enabled` (and `app.training.enabled` for training).
- New external URLs go in properties with a production default, so tests can override them.
- Operator-only endpoints go through `AdminEndpointFilter` (header `X-Admin-Token`, env `ADMIN_TOKEN`). Don't expose new actuator endpoints or springdoc publicly.
- Memory is tight on Railway (512 MB heap, see `Procfile`). Avoid loading large datasets into memory or adding heavy dependencies.
- Never commit secrets, API keys or `.env` files.

## Deployment gotchas

- Railway builds from the `Procfile`. Do **not** add a `Dockerfile` at the repo root; Railway would switch to a Docker build.
- Leave `src/system.properties` where it is unless deliberately changing the Railway Java runtime.
- `backtest_data/` and `models/` are runtime data directories (`/historical_data/...` on Railway).

## Working as the coding agent

When triggered from an issue (`agent:build` label) or an `@claude` comment:

1. Read the whole issue, including acceptance criteria and "Out of scope". If it's too vague to implement safely, reply with specific questions instead of guessing.
2. Work on your `claude/...` branch (or the PR's own branch when asked on a PR). Your commits go through the GitHub API to that branch only. Never try to change `master`, merge or approve; a push to `master` by anything other than a person fails CI and is never deployed.
3. For a bug: first write a test that reproduces it and fails, then fix the code until it passes.
4. Keep the change small and focused on the ticket. Don't refactor unrelated code, reformat files or bump dependencies unless asked.
5. Don't edit `.github/workflows/`, `Procfile`, `src/system.properties` or secrets. If the ticket needs that, say so and stop.
6. Run exactly `mvn -B -ntp verify` (the only shell command available to you; use Read/Grep/Glob to explore) and make sure it passes before committing. If it fails and you can't fix it, commit nothing and explain.
7. Finish with a short summary on the issue: what changed, which tests prove it, and anything you were unsure about. Tick the acceptance criteria you met.

## Git

- Commit author: `pxbt-dev <pxbt-dev@users.noreply.github.com>`. Exception: the coding agent's commits are authored by the Claude GitHub App (`claude[bot]`), which is expected.
- Default branch `master`. Work on a branch and open a PR; CI and a human review gate the merge. Only a person merging a PR may change `master` (enforced by the Guard job in CI plus Railway's "Wait for CI").
