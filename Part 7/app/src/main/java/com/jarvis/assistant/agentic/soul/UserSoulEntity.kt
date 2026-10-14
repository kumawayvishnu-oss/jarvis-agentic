package com.jarvis.assistant.agentic.soul

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a persistent personality trait, habit, preference, or behavioral pattern
 * learned dynamically about the user through conversational observation (Hermes-style).
 */
@Entity(
    tableName = "user_soul_attributes",
    indices = [
        Index(value = ["traitKey"], unique = true),
        Index(value = ["category"])
    ]
)
data class UserSoulEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val category: String, // PERSONALITY, PREFERENCES, HABITS, COMMUNICATION_STYLE, BELIEFS, ROUTINES
    val traitKey: String, // e.g. "prefers_short_answers", "tech_stack_android", "night_owl", "direct_tone"
    val traitValue: String, // e.g. "Values concise, direct replies with no fluff"
    val confidence: Float = 0.5f, // 0.0 to 1.0 confidence score
    val evidenceSummary: String = "", // Observation notes or user quotes that established this
    val observationCount: Int = 1, // How many times this pattern has been confirmed
    val lastObservedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CAT_PERSONALITY = "PERSONALITY"
        const val CAT_PREFERENCES = "PREFERENCES"
        const val CAT_HABITS = "HABITS"
        const val CAT_COMMUNICATION = "COMMUNICATION_STYLE"
        const val CAT_ROUTINES = "ROUTINES"
        const val CAT_VALUES = "BELIEFS_AND_VALUES"

        val ALL_CATEGORIES = listOf(
            CAT_PERSONALITY,
            CAT_PREFERENCES,
            CAT_HABITS,
            CAT_COMMUNICATION,
            CAT_ROUTINES,
            CAT_VALUES
        )
    }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault())
            return sdf.format(Date(updatedAt))
        }

    val key: String
        @Ignore
        get() = traitKey

    val value: String
        @Ignore
        get() = traitValue
}
