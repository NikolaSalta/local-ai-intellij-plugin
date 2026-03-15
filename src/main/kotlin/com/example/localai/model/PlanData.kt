package com.example.localai.model

/**
 * A single step in a structured plan response.
 */
data class PlanStep(
    val step: Int,
    val title: String,
    val description: String
)

/**
 * Wrapper for a complete plan response from the model.
 */
data class PlanResponse(
    val goal: String,
    val steps: List<PlanStep>
)
