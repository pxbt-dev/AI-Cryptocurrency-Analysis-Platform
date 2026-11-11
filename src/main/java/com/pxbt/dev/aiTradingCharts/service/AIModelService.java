package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.BacktestStats;
import com.pxbt.dev.aiTradingCharts.model.ModelPerformance;
import com.pxbt.dev.aiTradingCharts.util.WalkForwardBacktester;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.trees.M5P;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import weka.core.SerializationHelper;
import jakarta.annotation.PostConstruct;

@Slf4j
@Service
public class AIModelService {

    private final Map<String, Classifier> trainedModels = new ConcurrentHashMap<>();
    private final Map<String, ModelPerformance> modelPerformance = new ConcurrentHashMap<>();
    private final Map<String, Instances> dataHeaders = new ConcurrentHashMap<>();
    private final Map<String, Long> modelTrainingTimes = new ConcurrentHashMap<>();
    // Out-of-sample (walk-forward) backtest stats fed back from BacktestService after each run
    private final Map<String, BacktestStats> backtestStats = new ConcurrentHashMap<>();

    private static final double TRAINING_RATIO = 0.8;
    private static final int MIN_TRAINING_SAMPLES = 10;
    private static final String MODEL_DIR;

    static {
        MODEL_DIR = new java.io.File("/historical_data").exists()
                ? "/historical_data/models/"
                : "models/";
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Paths.get(MODEL_DIR));
            loadModelsFromDisk();
        } catch (Exception e) {
            log.error("❌ Failed to initialize model directory: {}", e.getMessage());
        }
    }

    /**
     * TRAINING with Weka ML library
     */

    public void trainModel(String symbol, String timeframe, List<double[]> featuresList, List<Double> targetChanges) {
        String key = generateKey(symbol, timeframe);
        if (featuresList.size() < MIN_TRAINING_SAMPLES) {
            log.warn("❌ Insufficient training data for {}: {} samples (need {})",
                    key, featuresList.size(), MIN_TRAINING_SAMPLES);
            return;
        }

        try {
            log.info("🤖 Training AI model for {} with {} samples", key, featuresList.size());

            // Create Weka dataset
            Instances dataset = createDataset(featuresList, targetChanges, symbol, timeframe);

            // Split data
            int trainSize = (int) (dataset.size() * TRAINING_RATIO);
            Instances trainData = new Instances(dataset, 0, trainSize);
            Instances testData = new Instances(dataset, trainSize, dataset.size() - trainSize);

            // Full dataset no longer needed - release it before training (saves RAM)
            dataset = null;

            // Train multiple models and select best
            Classifier bestModel = trainAndSelectBestModel(trainData, testData, timeframe);

            if (bestModel != null) {
                ModelPerformance performance = evaluateModel(bestModel, testData, trainSize);

                // Release training structures before storing model
                trainData = null;
                testData = null;

                trainedModels.put(key, bestModel);
                modelPerformance.put(key, performance);
                modelTrainingTimes.put(key, System.currentTimeMillis());

                saveModelToDisk(key);

                log.info("✅ Model trained & saved for {} - R2: {}, RMSE: {}",
                        key, String.format("%.4f", performance.getR2()), String.format("%.4f", performance.getRmse()));
            } else {
                trainData = null;
                testData = null;
                log.error("❌ No suitable model found for timeframe: {}", timeframe);
            }

        } catch (Exception e) {
            log.error("❌ AI training failed for {}: {}", timeframe, e.getMessage(), e);
        }
    }

    private Instances createDataset(List<double[]> featuresList, List<Double> targets, String symbol, String timeframe) {
        Instances dataset = buildDataset(featuresList, targets, timeframe);

        // Return a copy with 0 instances to store as header (saves RAM)
        Instances header = new Instances(dataset, 0);
        dataHeaders.put(generateKey(symbol, timeframe), header);

        // Clear input lists to free memory immediately
        featuresList.clear();
        targets.clear();

        return dataset;
    }

    /**
     * Fit a model the same way trainModel() does (chronological 80/20 split, best of LR / RF / M5P)
     * without storing, persisting or replacing the live model. Used by the walk-forward backtest.
     */
    public WalkForwardBacktester.FittedModel fitStandalone(List<double[]> featuresList, List<Double> targets, String timeframe) {
        if (featuresList.size() < MIN_TRAINING_SAMPLES) {
            return null;
        }
        try {
            Instances dataset = buildDataset(featuresList, targets, timeframe);
            int trainSize = (int) (dataset.size() * TRAINING_RATIO);
            Instances trainData = new Instances(dataset, 0, trainSize);
            Instances testData = new Instances(dataset, trainSize, dataset.size() - trainSize);
            Classifier model = trainAndSelectBestModel(trainData, testData, timeframe);
            if (model == null) {
                return null;
            }
            Instances header = new Instances(dataset, 0);
            String name = model.getClass().getSimpleName();
            return new WalkForwardBacktester.FittedModel() {
                public String name() {
                    return name;
                }

                public double predict(double[] features) {
                    try {
                        return applyPredictionBounds(model.classifyInstance(toInstance(features, header)));
                    } catch (Exception e) {
                        return 0.0;
                    }
                }
            };
        } catch (Exception e) {
            log.warn("⚠️ Standalone fit failed for {}: {}", timeframe, e.getMessage());
            return null;
        }
    }

    private static DenseInstance toInstance(double[] features, Instances header) {
        double[] instanceValues = new double[features.length + 1];
        System.arraycopy(features, 0, instanceValues, 0, features.length);
        instanceValues[features.length] = weka.core.Utils.missingValue(); // Target is missing for prediction
        DenseInstance instance = new DenseInstance(1.0, instanceValues);
        instance.setDataset(header);
        return instance;
    }

    private Instances buildDataset(List<double[]> featuresList, List<Double> targets, String timeframe) {
        // Create attributes
        ArrayList<Attribute> attributes = new ArrayList<>();

        // Add feature attributes
        for (int i = 0; i < featuresList.get(0).length; i++) {
            attributes.add(new Attribute("feature_" + i));
        }

        // Add target attribute
        attributes.add(new Attribute("price_change"));

        // Create dataset
        Instances dataset = new Instances("CryptoPrice_" + timeframe, attributes, featuresList.size());
        dataset.setClassIndex(dataset.numAttributes() - 1);

        // Add instances
        for (int i = 0; i < featuresList.size(); i++) {
            double[] features = featuresList.get(i);
            double target = targets.get(i);

            double[] instanceValues = new double[features.length + 1];
            System.arraycopy(features, 0, instanceValues, 0, features.length);
            instanceValues[features.length] = target;

            dataset.add(new DenseInstance(1.0, instanceValues));
        }

        return dataset;
    }

    private Classifier trainAndSelectBestModel(Instances trainData, Instances testData, String timeframe) {
        Classifier bestModel = null;
        double bestScore = -Double.MAX_VALUE;

        // 1. Linear Regression
        try {
            LinearRegression lr = new LinearRegression();
            lr.buildClassifier(trainData);
            double score = calculateRSquared(lr, testData);
            log.info("📊 Linear Regression R²: {}", String.format("%.4f", score));
            bestModel = lr;
            bestScore = score;
        } catch (Exception e) {
            log.warn("⚠️ Linear Regression failed: {}", e.getMessage());
        }

        // 2. Random Forest
        try {
            RandomForest rf = new RandomForest();
            rf.setNumExecutionSlots(1); // CRITICAL: Stop multi-threaded memory spikes on 16-core hosts
            rf.setNumIterations(50);    // Raised from 10 — monitor memory
            rf.setMaxDepth(15);        // Raised from 8 — monitor memory
            rf.buildClassifier(trainData);
            double score = calculateRSquared(rf, testData);
            log.info("📊 Random Forest R²: {}", String.format("%.4f", score));
            if (score > bestScore) {
                bestModel = rf;
                bestScore = score;
            } else {
                rf = null;
            }
        } catch (Exception e) {
            log.warn("⚠️ Random Forest failed: {}", e.getMessage());
        }

        // 3. M5P (model tree — lighter than RF, often better on tabular time-series)
        try {
            M5P m5p = new M5P();
            m5p.setMinNumInstances(10);
            m5p.buildClassifier(trainData);
            double score = calculateRSquared(m5p, testData);
            log.info("📊 M5P R²: {}", String.format("%.4f", score));
            if (score > bestScore) {
                bestModel = m5p;
                bestScore = score;
            }
        } catch (Exception e) {
            log.warn("⚠️ M5P failed: {}", e.getMessage());
        }

        return bestModel;
    }

    private Classifier selectBestModel(Map<String, Classifier> models, Map<String, Double> scores) {
        if (scores.isEmpty())
            return null;

        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> {
                    log.info("🏆 Best model: {} with R²: {}", entry.getKey(), entry.getValue());
                    return models.get(entry.getKey());
                })
                .orElse(null);
    }

    private double calculateRSquared(Classifier model, Instances testData) throws Exception {
        double ssTotal = 0;
        double ssResidual = 0;
        double mean = 0;

        // Calculate mean
        for (int i = 0; i < testData.size(); i++) {
            mean += testData.get(i).classValue();
        }
        mean /= testData.size();

        for (int i = 0; i < testData.size(); i++) {
            double actual = testData.get(i).classValue();
            double prediction = model.classifyInstance(testData.get(i));

            ssTotal += Math.pow(actual - mean, 2);
            ssResidual += Math.pow(actual - prediction, 2);
        }

        return 1 - (ssResidual / ssTotal);
    }

    /**
     * Evaluate model performance using Weka's Evaluation class
     */
    private ModelPerformance evaluateModel(Classifier model, Instances testData, int trainSize) {
        try {
            Evaluation eval = new Evaluation(testData);
            eval.evaluateModel(model, testData);

            double r2 = calculateRSquared(model, testData);
            double rmse = eval.rootMeanSquaredError();
            double mae = eval.meanAbsoluteError();

            return new ModelPerformance(Math.max(0, r2), rmse, mae, trainSize, testData.size());

        } catch (Exception e) {
            log.error("❌ Model evaluation failed: {}", e.getMessage());
            return new ModelPerformance(0.0, 1.0, 1.0, trainSize, testData.size());
        }
    }

    /**
     * AI PREDICTION
     */
    public double predictPriceChange(String symbol, double[] features, String timeframe) {
        String key = generateKey(symbol, timeframe);
        Classifier model = trainedModels.get(key);
        Instances header = dataHeaders.get(key);

        if (model == null || header == null) {
            log.warn("⚠️ Cannot predict for {}: Missing model or header", key);
            return 0.0;
        }

        // Safety check: ensure features size matches model expectation
        // Header includes N features + 1 target attribute
        if (features.length != header.numAttributes() - 1) {
            log.warn("🚨 Feature mismatch for {}: Model expects {} but got {}. Invalidating old model.", 
                    key, header.numAttributes() - 1, features.length);
            trainedModels.remove(key);
            dataHeaders.remove(key);
            return 0.0;
        }

        try {
            double prediction = model.classifyInstance(toInstance(features, header));
            prediction = applyPredictionBounds(prediction);

            log.debug("🤖 AI Prediction for {}: {}% change", timeframe, prediction * 100);
            return prediction;

        } catch (Exception e) {
            log.error("❌ AI prediction failed for {}: {}", timeframe, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Get prediction with confidence score
     */
    public Map<String, Object> predictWithConfidence(String symbol, double[] features, String timeframe) {
        Map<String, Object> result = new HashMap<>();
        String key = generateKey(symbol, timeframe);

        if (!trainedModels.containsKey(key)) {
            result.put("prediction", 0.0);
            result.put("confidence", 0.0);
            result.put("model", "none");
            return result;
        }

        try {
            Classifier model = trainedModels.get(key);
            double prediction = predictPriceChange(symbol, features, timeframe);

            ModelPerformance perf = modelPerformance.get(key);
            BacktestStats stats = backtestStats.get(key);

            result.put("prediction", prediction);
            result.put("confidence", calculateConfidence(stats));
            result.put("model", model.getClass().getSimpleName());
            result.put("rScore", perf != null ? perf.getR2() : 0.0);
            // Use the AI output only when it has a proven out-of-sample edge; otherwise the technical fallback
            result.put("isReliable", stats != null && stats.hasEdge());

            return result;

        } catch (Exception e) {
            log.error("❌ Confidence prediction failed: {}", e.getMessage());
            result.put("prediction", 0.0);
            result.put("confidence", 0.0);
            result.put("model", "error");
            return result;
        }
    }

    /**
     * Confidence = evidence that the model beats chance, measured out of sample (walk-forward backtest).
     * 0 means no demonstrated edge. Half the score comes from the direction win rate (its 95% lower
     * bound, so small samples can't score high by luck; 60% = full marks), half from how much smaller
     * the error is than a "no change" forecast (10% smaller = full marks).
     */
    static double calculateConfidence(BacktestStats stats) {
        if (stats == null || stats.samples() == 0)
            return 0.0;
        double directionScore = clamp01((stats.winRateLowerBound() - 0.5) / 0.10);
        double errorScore = clamp01(stats.skill() / 0.10);
        return 0.5 * directionScore + 0.5 * errorScore;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private double applyPredictionBounds(double prediction) {
        // Limit predictions to reasonable bounds (±20%)
        return Math.max(-0.2, Math.min(0.2, prediction));
    }

    /**
     * Get model performance metrics
     */
    public ModelPerformance getModelPerformance(String symbol, String timeframe) {
        return modelPerformance.get(generateKey(symbol, timeframe));
    }

    /**
     * Check if model is trained and ready
     */
    public boolean isModelTrained(String symbol, String timeframe) {
        String key = generateKey(symbol, timeframe);
        return trainedModels.containsKey(key) &&
                modelPerformance.get(key) != null &&
                modelPerformance.get(key).getR2() > 0.1;
    }

    private String generateKey(String symbol, String timeframe) {
        return (symbol.toUpperCase() + "_" + timeframe.toLowerCase());
    }

    /**
     * Get all trained timeframes
     */
    public List<String> getTrainedTimeframes() {
        return new ArrayList<>(trainedModels.keySet());
    }

    /**
     * Get model information for monitoring
     */
    public Map<String, Object> getModelInfo(String symbol, String timeframe) {
        Map<String, Object> info = new HashMap<>();
        String key = generateKey(symbol, timeframe);
        if (trainedModels.containsKey(key)) {
            Classifier model = trainedModels.get(key);
            ModelPerformance perf = modelPerformance.get(key);

            info.put("modelType", model.getClass().getSimpleName());
            info.put("trained", true);
            info.put("performance", perf);
        } else {
            info.put("trained", false);
            info.put("performance", null);
        }
        return info;
    }

    /**
     * Get the number of trained models
     */
    public int getTrainedModelCount() {
        return trainedModels.size();
    }

    public Long getLastTrainingTime(String timeframe) {
        return modelTrainingTimes.get(timeframe);
    }

    public Long getOverallLastTrainingTime() {
        return modelTrainingTimes.values().stream()
                .max(Long::compare)
                .orElse(0L);
    }

    /**
     * Called by BacktestService after each walk-forward run (and at startup from persisted records).
     */
    public void updateBacktestStats(String symbol, String timeframe, BacktestStats stats) {
        String key = generateKey(symbol, timeframe);
        backtestStats.put(key, stats);
        log.info("📈 Out-of-sample stats for {}: n={}, win {}%, skill vs no-change {}%, edge={}",
                key, stats.samples(), String.format("%.1f", stats.winRate() * 100),
                String.format("%+.1f", stats.skill() * 100), stats.hasEdge());
    }
    private void saveModelToDisk(String tfKey) {
        Classifier model = trainedModels.get(tfKey);
        ModelPerformance perf = modelPerformance.get(tfKey);
        Instances header = dataHeaders.get(tfKey);

        if (model == null || perf == null || header == null) {
            log.warn("⚠️ Skipping save for {}: partial data only", tfKey);
            return;
        }

        try {
            String path = MODEL_DIR + tfKey;
            SerializationHelper.write(path + ".model", model);
            SerializationHelper.write(path + ".perf", perf);
            SerializationHelper.write(path + ".header", header);
            log.info("💾 Saved AI model state for {} to disk", tfKey);
        } catch (Exception e) {
            log.error("❌ Failed to save model {} to disk: {}", tfKey, e.getMessage());
        }
    }

    private void loadModelsFromDisk() {
        File dir = new File(MODEL_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".model"));
        if (files == null || files.length == 0)
            return;
    
        log.info("📂 Hub: Restoring AI models from disk...");
        for (File file : files) {
            String key = file.getName().replace(".model", ""); // key is symbol_timeframe
            try {
                // Atomic verification: only load if all 3 parts exist
                File perfFile = new File(MODEL_DIR + key + ".perf");
                File headerFile = new File(MODEL_DIR + key + ".header");

                if (!perfFile.exists() || !headerFile.exists()) {
                    log.warn("⚠️ Skipping {} model: missing .perf or .header files", key);
                    continue;
                }

                Classifier model = (Classifier) SerializationHelper.read(file.getAbsolutePath());
                ModelPerformance perf = (ModelPerformance) SerializationHelper.read(perfFile.getAbsolutePath());
                Instances header = (Instances) SerializationHelper.read(headerFile.getAbsolutePath());

                if (model != null && perf != null && header != null) {
                    trainedModels.put(key, model);
                    modelPerformance.put(key, perf);
                    dataHeaders.put(key, header);
                    modelTrainingTimes.put(key, file.lastModified());
                    log.info("✅ Restored {} model", key);
                }
            } catch (Exception e) {
                log.warn("⚠️ Could not restore model {}: {}", key, e.getMessage());
            }
        }
    }
}