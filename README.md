# AI Cryptocurrency Analysis Platform 🤖

A _experimenal_ AI-powered cryptocurrency analysis and predeiction platform that uses real-time market data and WEKA machine learning for price predictions for 4 cryptocurrencies - BTC, SOL, TAO and WIF. A work in progress.

![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-6DB33F?logo=springboot&logoColor=white)
![HTML5](https://img.shields.io/badge/HTML5-E34F26?logo=html5&logoColor=white)
![CSS3](https://img.shields.io/badge/CSS3-1572B6?logo=css3&logoColor=white)
![JavaScript](https://img.shields.io/badge/JavaScript-F7DF1E?logo=javascript&logoColor=black)
![Railway](https://img.shields.io/badge/Deploy-Railway-0B0D0E?logo=railway&logoColor=white)
![Weka](https://img.shields.io/badge/Weka-Machine%20Learning-007EC6?logo=weka&logoColor=white)

📊 Live- https://web-production-5aef.up.railway.app/

### Features 
🔍 Multi-timeframe Analysis: Simultaneous analysis using ML across 1H, 4H, 1D, and 1W timeframes

📊 Real-time Market Data: Live cryptocurrency prices from Binance WebSocket feeds

📈 Interactive Charts: Candlestick charts with timeframe selection

⚡ Real-time Updates: WebSocket-based live data streaming and analysis

###  ML Pipeline:
Raw Price Data >  15 Technical Indicators > Feature Vector [SMA5, EMA12, RSI14, MACD, ...] > Weka ML Model Training (3 algorithms) > Real-time Price Predictions with Confidence Scores

### ML uses these features to predict price:
- **Trend Analysis**: SMA(5,20,50,200), EMA(12,26)
- **Momentum**: RSI(14), MACD, Price Acceleration  
- **Volatility**: Standard Deviation, Z-Score, Bollinger Bands
- **Volume**: Volume-Price Trend, Volume Strength
- **Market Structure**: Support/Resistance, Market Cycles

### Models Trained:
 **Automated Model Selection**: System trains Linear Regression, SVM, and Random Forest
- **Best Model Per Timeframe**: Selects highest-performing model (based on R² score) for each timeframe
- **Single Model Prediction**: Uses only the best model for actual price predictions

### Prediction Timeframes:
- 1 hour, 4 hours, 1 day, 1 week





