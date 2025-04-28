package com.example.alertdrive

data class PredictionResult(
    val label: String,
    val confidence: Float,
    val features: List<Float>
)