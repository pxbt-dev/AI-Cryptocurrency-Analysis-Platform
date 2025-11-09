package com.pxbt.dev.aiTradingCharts.service;

import com.pxbt.dev.aiTradingCharts.model.ModelPerformance;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.functions.SMOreg;
import weka.classifiers.trees.RandomForest;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instances;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Random;

@Slf4j
@Service
public class AIModelService {

    @Autowired
    BinanceHistoricalService binanceHistoricalService;

    private Map<String, Classifier> trainedModels = new ConcurrentHashMap<>();
    private Map<String, ModelPerformance> modelPerformance = new ConcurrentHashMap<>();
    private Map<String, Instances> dataHeaders = new ConcurrentHashMap<>();

    private static final double TRAINING_RATIO = 0.8;
    private static final int MIN_TRAINING_SAMPLES = 50;
    private final Random random = new Random();

    /**
     * REAL AI TRAINING with Weka ML library
     */

    public void trainModel(String timeframe, List<double[]> featuresList, List<Double> targetChanges) {
        if (featuresList.size() < MIN_TRAINING_SAMPLES) {
            log.warn("❌ Insufficient training data for {}: {} samples (need {})",
                    timeframe, featuresList.size(), MIN_TRAINING_SAMPLES);
            return;
        }

        try {
            log.info("🤖 Training REAL AI model for {} with {} samples", timeframe, featuresList.size());

            // Create Weka dataset
            Instances dataset = createDataset(featuresList, targetChanges, timeframe);
            dataHeaders.put(timeframe, dataset);

            // Split data
            int trainSize = (int) (dataset.size() * TRAINING_RATIO);
            Instances trainData = new Instances(dataset, 0, trainSize);
            Instances testData = new Instances(dataset, trainSize, dataset.size() - trainSize);

            // Train multiple models and select best
            Classifier bestModel = trainAndSelectBestModel(trainData, testData, timeframe);

            if (bestModel != null) {
                trainedModels.put(timeframe, bestModel);
                ModelPerformance performance = evaluateModel(bestModel, testData);
                modelPerformance.put(timeframe, performance);

                log.info("✅ REAL AI Model trained for {} - R²: {:.4f}, RMSE: {:.6f}",
                        timeframe, performance.getR2(), performance.getRmse());
            } else {
                log.error("❌ No suitable model found for timeframe: {}", timeframe);
            }

        } catch (Exception e) {
            log.error("❌ AI training failed for {}: {}", timeframe, e.getMessage(), e);
        }
    }

    private Instances createDataset(List<double[]> featuresList, List<Double> targets, String timeframe) {
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
        Map<String, Classifier> models = new HashMap<>();
        Map<String, Double> modelScores = new HashMap<>();

        try {
            // 1. Linear Regression
            LinearRegression lr = new LinearRegression();
            lr.buildClassifier(trainData);
            models.put("LinearRegression", lr);
            double lrScore = calculateRSquared(lr, testData);
            modelScores.put("LinearRegression", lrScore);
            log.debug("📊 Linear Regression R²: {:.4f}", lrScore);

        } catch (Exception e) {
            log.warn("⚠️ Linear Regression failed: {}", e.getMessage());
        }

        try {
            // 2. Support Vector Regression
            SMOreg svm = new SMOreg();
            svm.buildClassifier(trainData);
            models.put("SVM", svm);
            double svmScore = calculateRSquared(svm, testData);
            modelScores.put("SVM", svmScore);
            log.debug("📊 SVM R²: {:.4f}", svmScore);

        } catch (Exception e) {
            log.warn("⚠️ SVM failed: {}", e.getMessage());
        }

        try {
            // 3. Random Forest
            RandomForest rf = new RandomForest();
            rf.setNumIterations(100);
            rf.buildClassifier(trainData);
            models.put("RandomForest", rf);
            double rfScore = calculateRSquared(rf, testData);
            modelScores.put("RandomForest", rfScore);
            log.debug("📊 Random Forest R²: {:.4f}", rfScore);

        } catch (Exception e) {
            log.warn("⚠️ Random Forest failed: {}", e.getMessage());
        }

        return selectBestModel(models, modelScores);
    }

    private Classifier selectBestModel(Map<String, Classifier> models, Map<String, Double> scores) {
        if (scores.isEmpty()) return null;

        return scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(entry -> {
                    log.info("🏆 Best model: {} with R²: {:.4f}", entry.getKey(), entry.getValue());
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
    private ModelPerformance evaluateModel(Classifier model, Instances testData) {
        try {
            Evaluation eval = new Evaluation(testData);
            eval.evaluateModel(model, testData);

            double r2 = eval.correlationCoefficient(); // This is R² in Weka
            double rmse = eval.rootMeanSquaredError();
            double mae = eval.meanAbsoluteError();

            return new ModelPerformance(r2 * r2, rmse, mae, testData.size()); // correlationCoefficient returns R, so square it for R²

        } catch (Exception e) {
            log.error("❌ Model evaluation failed: {}", e.getMessage());
            return new ModelPerformance(0.0, 1.0, 1.0, testData.size());
        }
    }

    /**
     * REAL AI PREDICTION
     */
    public double predictPriceChange(double[] features, String timeframe) {
        if (!trainedModels.containsKey(timeframe)) {
            log.warn("⚠️ No trained model for {}", timeframe);
            return 0.0;
        }

        try {
            Classifier model = trainedModels.get(timeframe);
            Instances header = dataHeaders.get(timeframe);

            // Create instance for prediction
            double[] instanceValues = new double[features.length + 1];
            System.arraycopy(features, 0, instanceValues, 0, features.length);
            instanceValues[features.length] = weka.core.Utils.missingValue(); // Target is missing for prediction

            DenseInstance instance = new DenseInstance(1.0, instanceValues);
            instance.setDataset(header);

            double prediction = model.classifyInstance(instance);
            prediction = applyPredictionBounds(prediction);

            log.debug("🤖 AI Prediction for {}: {:.4f}% change", timeframe, prediction * 100);
            return prediction;

        } catch (Exception e) {
            log.error("❌ AI prediction failed for {}: {}", timeframe, e.getMessage());
            return 0.0;
        }
    }

    /**
     * Get prediction with confidence score
     */
    public Map<String, Object> predictWithConfidence(double[] features, String timeframe) {
        Map<String, Object> result = new HashMap<>();

        if (!trainedModels.containsKey(timeframe)) {
            result.put("prediction", 0.0);
            result.put("confidence", 0.1);
            result.put("model", "none");
            return result;
        }

        try {
            Classifier model = trainedModels.get(timeframe);
            double prediction = predictPriceChange(features, timeframe);

            ModelPerformance perf = modelPerformance.get(timeframe);
            double confidence = calculatePredictionConfidence(prediction, perf);

            result.put("prediction", prediction);
            result.put("confidence", confidence);
            result.put("model", model.getClass().getSimpleName());

            return result;

        } catch (Exception e) {
            log.error("❌ Confidence prediction failed: {}", e.getMessage());
            result.put("prediction", 0.0);
            result.put("confidence", 0.1);
            result.put("model", "error");
            return result;
        }
    }

    private double calculatePredictionConfidence(double prediction, ModelPerformance perf) {
        if (perf == null) return 0.5;

        double baseConfidence = Math.max(0.1, Math.min(0.9, perf.getR2()));

        // Reduce confidence for extreme predictions
        double predictionMagnitude = Math.abs(prediction);
        if (predictionMagnitude > 0.1) { // >10% change
            baseConfidence *= 0.7;
        } else if (predictionMagnitude > 0.05) { // >5% change
            baseConfidence *= 0.85;
        }

        return Math.max(0.1, Math.min(0.95, baseConfidence));
    }

    private double applyPredictionBounds(double prediction) {
        // Limit predictions to reasonable bounds (±20%)
        return Math.max(-0.2, Math.min(0.2, prediction));
    }

    /**
     * Get model performance metrics
     */
    public ModelPerformance getModelPerformance(String timeframe) {
        return modelPerformance.get(timeframe);
    }

    /**
     * Check if model is trained and ready
     */
    public boolean isModelTrained(String timeframe) {
        return trainedModels.containsKey(timeframe) &&
                modelPerformance.get(timeframe) != null &&
                modelPerformance.get(timeframe).getR2() > 0.1;
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
    public Map<String, Object> getModelInfo(String timeframe) {
        Map<String, Object> info = new HashMap<>();
        if (trainedModels.containsKey(timeframe)) {
            Classifier model = trainedModels.get(timeframe);
            ModelPerformance perf = modelPerformance.get(timeframe);

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
}