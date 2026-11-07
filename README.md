# AI Cryptocurrency Analysis Platform 🤖

A _experimenal_ AI-powered cryptocurrency analysis and predeiction platform that uses real-time market data and WEKA machine learning for price predictions for 4 cryptocurrencies - BTC, SOL, TAO and WIF. A work in progress.

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?logo=springboot&logoColor=white)
![HTML5](https://img.shields.io/badge/HTML5-E34F26?logo=html5&logoColor=white)
![CSS3](https://img.shields.io/badge/CSS3-1572B6?logo=css3&logoColor=white)
![JavaScript](https://img.shields.io/badge/JavaScript-F7DF1E?logo=javascript&logoColor=black)
![Railway](https://img.shields.io/badge/Deploy-Railway-0B0D0E?logo=railway&logoColor=white)
![Weka](https://img.shields.io/badge/Weka-Machine%20Learning-007EC6?logo=weka&logoColor=white)

📊 Live- https://aicryptopredictor.com/

### Features 
🔍 **Multi-timeframe Analysis**: Simultaneous analysis using ML across 1D, 1W and 1M timeframes.

📊 **Real-time Market Data**: Live cryptocurrency prices from Binance WebSocket feeds.

📈 **Interactive Charts**: Candlestick charts with timeframe selection and historical prediction overlays.

⚡ **Real-time Updates**: WebSocket-based live data streaming and analysis.

🧪 **Backtesting & Accuracy Audit**: 
- **Visual Backtest**: View historical AI predictions plotted directly on the price charts.
- **Color-coded Performance**: Green/Red segments clearly identify successful vs. failed directional predictions.
- **Audit Logs**: Deep-dive into historical evidence tables with win rates and average error metrics.
- **Automated Refresh**: Backtest results are refreshed every 6 hours and on system startup.

###  ML Pipeline:
Raw Price Data >  15 Technical Indicators > Feature Vector [SMA5, EMA12, RSI14, MACD, ...] > Weka ML Model Training (3 algorithms) > Real-time Price Predictions with Confidence Scores

### ML uses these features to predict price:
- **Trend Analysis**: SMA(5,20,50,200), EMA(12,26)
- **Momentum**: RSI(14), MACD, Price Acceleration  
- **Volatility**: Standard Deviation, Z-Score, Bollinger Bands
- **Volume**: Volume-Price Trend, Volume Strength
- **Market Structure**: Support/Resistance, Market Cycles

### Models Trained:
- **Automated Model Selection**: System trains Linear Regression, SVM, and Random Forest
- **Best Model Per Timeframe**: Selects highest-performing model (based on R² score) for each timeframe
- **Single Model Prediction**: Uses only the best model for actual price predictions

**Known Limitations:**
- ML models trained on limited historical data (from 2019)
- No backtesting framework implemented
- Basic feature engineering (15 indicators)
- Real-time predictions may lag during high volatility

### Prediction Timeframes:
- 1 day, 1 week, 1 month


⚠️ **This is an experimental project**: Predictions should not be used for actual trading decisions.








### Delivery pipeline

Every change, whether written by you or by the coding agent, takes the same path to production. Each box is a GitHub Actions job, a Railway step or a decision you make; the three workflows are linked by events.

```mermaid
flowchart TD
    I["Issue with acceptance criteria"] -->|"label agent:build"| A["Coding agent<br/>(claude-dev.yml)<br/>branch + failing test + fix + mvn verify"]
    A -->|"Create PR link"| PR{{"You open the PR"}}
    H["You push a branch"] --> PR

    subgraph CI ["CI on the PR (ci.yml)"]
        direction LR
        B["Build"] --> U["Unit tests<br/>unit + API contract"] --> UT["UI tests<br/>app on runner,<br/>Binance stubbed"] --> R["AI review<br/>advisory"]
    end
    PR --> B

    R -->|"all checks green<br/>(Railway: Wait for CI)"| P["Railway PR preview<br/>*.up.railway.app"]
    P -->|"deploy succeeded"| S["UI smoke<br/>(ui-smoke.yml)<br/>Playwright vs preview"]
    S --> M{{"You review and merge"}}
    M -->|"merge deletes the preview"| X["Preview removed"]

    subgraph MASTER ["CI on master (ci.yml)"]
        direction LR
        G["Guard<br/>pushed by a person?"] --> B2["Build"] --> U2["Unit tests"] --> UT2["UI tests"]
    end
    M --> G
    UT2 -->|"all green<br/>(Wait for CI)"| D["Production<br/>www.aicryptopredictor.com"]
    G -.->|"bot push: Guard fails,<br/>nothing deploys"| N["Blocked"]
```

- **Backtest** (live data) is a manual extra on the CI workflow: Actions → CI → Run workflow → tick "run backtest".
- **UI tests run twice on a PR:** in CI against the app started on the runner with stubbed market data (fast, deterministic), then against the real Railway preview (proves the deployed build works).
- **Where to look:** a PR's checks and statuses show CI, the AI review, Railway and UI smoke together; the Actions tab lists each workflow's runs.

### Running tests

```bash
mvn -B verify              # offline unit tests + JaCoCo report (target/site/jacoco)
mvn -B verify -Pbacktest   # live-data backtest against Binance (slow, needs internet)
```

Tests use the `test` Spring profile (`src/test/resources/application-test.properties`), which switches off ML training, Binance WebSockets, startup preloads and scheduled jobs. External APIs are stubbed with WireMock. CI runs the same build on every pull request (`.github/workflows/ci.yml`).

### Admin endpoints

`POST /api/system/train`, `GET /api/backtest/run` and `/api/debug/**` require an `X-Admin-Token` header matching the `ADMIN_TOKEN` environment variable. If `ADMIN_TOKEN` is not set, they return 404.

### API docs

Swagger UI and `/v3/api-docs` are switched off by default so they are not published on the live site. To view them locally, run with `SPRINGDOC_API_DOCS_ENABLED=true SPRINGDOC_SWAGGER_UI_ENABLED=true` and open `/swagger-ui/index.html`.
