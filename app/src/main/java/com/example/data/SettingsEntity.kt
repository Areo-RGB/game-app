package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = 1,
    val initTime: Int = 10000,
    val minTime: Int = 1000,
    val reduction: Int = 250,
    val scaleInterval: Int = 60000,
    val urgentMs: Int = 3000,
    val fullscreen: Boolean = true,
    val highScore: Int = 0,
    val isReverseMode: Boolean = false,
    val reverseLimitMs: Long = 300000L, // 5 minutes standard
    val reverseHighScore: Int = 0,
    val livesEnabled: Boolean = true,
    val livesCount: Int = 5,
    val isController: Boolean = false
)
