# aiCryptoPredictor

Spring Boot 3.1 / Java 21 app behind www.aicryptopredictor.com. It pulls Binance market data, trains Weka models per symbol and timeframe, and serves a Chart.js dashboard (`templates/chart.html`) plus a JSON API. Deployed on Railway from `master`.

## Build and test

```bash
mvn -B verify              # offline unit + Spring tests, JaCoCo report in target/site/jacoco
mvn -B verify -Pbacktest   # live-data backtest (BacktestRunner, @Tag("backtest")); slow, needs Binance
```

CI (`.github/workflows/ci.yml`) runs `mvn -B verify` on every PR. It must stay green.

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

## Git

- Commit author: `pxbt-dev <pxbt-dev@users.noreply.github.com>`.
- Default branch `master`. Work on a branch and open a PR; CI and a human review gate the merge.
