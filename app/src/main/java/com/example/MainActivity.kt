package com.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startupRole = DevicePresets.presetFor(this).role
        if (startupRole == DeviceRole.DISPLAY) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@Composable
private fun ConfigureSystemBars(immersive: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return

    DisposableEffect(window, view, lifecycleOwner, immersive) {
        fun applySystemBars() {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            if (immersive) {
                controller.isAppearanceLightStatusBars = false
                controller.isAppearanceLightNavigationBars = false
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
            }
        }

        applySystemBars()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applySystemBars()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

// Custom ImageVectors defined programmatically to be totally self-contained and bulletproof
val AddIcon = Icons.Default.Add

val RemoveIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Remove",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(5f, 12f)
            lineTo(19f, 12f)
        }
    }.build()

val ClockIcon: ImageVector
    get() = ImageVector.Builder(
        name = "Clock",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Draw circle outline
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
            // Draw hands
            moveTo(12f, 6f)
            lineTo(12f, 12f)
            lineTo(16f, 14f)
        }
    }.build()

@Composable
fun MainAppScreen(viewModel: MainViewModel = viewModel()) {
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val localRole = viewModel.localRole
    val showTabBar = localRole != DeviceRole.DISPLAY && !(activeTab == ActiveTab.GAME && settings.fullscreen)

    ConfigureSystemBars(immersive = true)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.White,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (showTabBar) {
                // Precise Navigation Tab implementation matching original web 12dp/48px design
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(Color(0xFFF9FAFB))
                        .testTag("navigation_tabs")
                ) {
                    if (localRole != DeviceRole.DISPLAY) {
                        TabButton(
                            label = "Game",
                            icon = ClockIcon,
                            isActive = activeTab == ActiveTab.GAME,
                            onClick = { viewModel.setActiveTab(ActiveTab.GAME) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        TabButton(
                            label = "Display",
                            icon = ClockIcon,
                            isActive = activeTab == ActiveTab.DISPLAY,
                            onClick = { viewModel.setActiveTab(ActiveTab.DISPLAY) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    VerticalDivider(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp),
                        color = Color(0xFFE5E7EB)
                    )
                    if (settings.isController) {
                        TabButton(
                            label = "Game Settings",
                            icon = Icons.Default.Settings,
                            isActive = activeTab == ActiveTab.GAME_SETTINGS,
                            onClick = { viewModel.setActiveTab(ActiveTab.GAME_SETTINGS) },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        TabButton(
                            label = "Settings",
                            icon = Icons.Default.Settings,
                            isActive = activeTab == ActiveTab.SETTINGS,
                            onClick = { viewModel.setActiveTab(ActiveTab.SETTINGS) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (showTabBar) innerPadding else PaddingValues(0.dp))
        ) {
            when (activeTab) {
                ActiveTab.GAME -> GameTabContent(viewModel)
                ActiveTab.SETTINGS -> SettingsTabContent(viewModel)
                ActiveTab.GAME_SETTINGS -> GameSettingsTabContent(viewModel)
                ActiveTab.DISPLAY -> DisplayTabContent(viewModel)
            }
        }
    }
}

@Composable
fun RowScope.TabButton(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .background(if (isActive) Color.White else Color.Transparent)
            .testTag("${label.lowercase()}_tab_button"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isActive) Color(0xFF111827) else Color(0xFF9CA3AF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isActive) Color(0xFF111827) else Color(0xFF9CA3AF)
                )
            }
            // Line indicator at the bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(if (isActive) Color(0xFF111827) else Color.Transparent)
            )
        }
    }
}

@Composable
fun GameTabContent(viewModel: MainViewModel) {
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val round by viewModel.round.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()
    val remaining by viewModel.remaining.collectAsStateWithLifecycle()
    val sessionStart by viewModel.sessionStart.collectAsStateWithLifecycle()
    val elapsedTime by viewModel.elapsedTime.collectAsStateWithLifecycle()
    val stackedTime by viewModel.stackedTime.collectAsStateWithLifecycle()
    val remainingLives by viewModel.remainingLives.collectAsStateWithLifecycle()

    val isUrgent = !settings.isReverseMode && gameState == MyGameState.RUNNING && remaining <= settings.urgentMs

    // Interaction Source to block default clicks leaking
    val interactionSource = remember { MutableInteractionSource() }

    val successFeedback = remember { Animatable(0f) }
    LaunchedEffect(round, gameState) {
        if (gameState == MyGameState.RUNNING && round > 1) {
            successFeedback.snapTo(1f)
            successFeedback.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            )
        }
    }
    val successPulse = successFeedback.value

    fun formatMMSS(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    fun nextDifficultyText(): String {
        if (settings.isReverseMode) return "Nächste Schwierigkeit: Reverse Mode nutzt kein Scale-Intervall"
        if (!settings.autoDifficultyEnabled) {
            return "Manuelles Limit: ${settings.initTime} ms  •  Schritt ${settings.manualLimitStepMs} ms"
        }
        if (gameState != MyGameState.RUNNING || sessionStart == 0L) {
            return "Nächste Schwierigkeit in ${settings.scaleInterval} ms"
        }
        val elapsed = (System.currentTimeMillis() - sessionStart).coerceAtLeast(0L)
        val interval = settings.scaleInterval.toLong().coerceAtLeast(1L)
        val untilNext = interval - (elapsed % interval)
        return "Nächste Schwierigkeit in $untilNext ms"
    }

    if (settings.fullscreen) {
        // --- Fullscreen Immersive layout mode ---
        val backgroundColor = when (gameState) {
            MyGameState.IDLE -> Color(0xFF030712) // gray-950
            MyGameState.RUNNING -> {
                if (settings.isReverseMode) {
                    Color(0xFF0F172A) // Slate-900 color for stopwatch ticks
                } else if (isUrgent) {
                    Color(0xFFEF4444) // red-500 or emerald-600
                } else {
                    Color(0xFF059669)
                }
            }
            MyGameState.FAILED -> Color(0xFF7F1D1D) // red-900 / dark red
            MyGameState.FINISHED -> Color(0xFF1E1B4B) // Dark Indigo
        }

        // Animated pulse scaling in urgent state
        val pulseScale = if (isUrgent) {
            val infiniteTransition = rememberInfiniteTransition(label = "immersivePulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )
            scale
        } else {
            1.0f
        }

        // Immersive Fullscreen Interactive Box
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .graphicsLayer(
                    scaleX = pulseScale + (successPulse * 0.035f),
                    scaleY = pulseScale + (successPulse * 0.035f)
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null, // No ripple in full screen mode for raw tactile immersion
                    onClick = { viewModel.handlePress() }
                )
                .testTag("fullscreen_game_panel"),
            contentAlignment = Alignment.Center
        ) {
            if (successPulse > 0f) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = 0.20f * successPulse))
                        .testTag("success_press_flash")
                )
            }

            // Floating Settings Cog on top-right corner to exit fullscreen
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .clickable { viewModel.setActiveTab(ActiveTab.SETTINGS) }
                        .testTag("fullscreen_settings_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open Settings",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Top immersive thin progress bar strip
            if (gameState == MyGameState.RUNNING) {
                if (settings.isReverseMode && settings.reverseLimitMs > 0) {
                    val fraction = ((stackedTime + elapsedTime).toFloat() / settings.reverseLimitMs).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(Color.Black.copy(alpha = 0.15f))
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(Color(0xFF38BDF8)) // Sky blue progress strip
                        )
                    }
                } else if (countdown > 0) {
                    val fraction = remaining.toFloat() / countdown
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(Color.Black.copy(alpha = 0.15f))
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(if (isUrgent) Color.White else Color(0xFFA7F3D0)) // white or emerald-200
                        )
                    }
                }
            }

            // Large center texts
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                when (gameState) {
                    MyGameState.IDLE -> {
                        Text(
                            text = "START",
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1.5).sp,
                            color = Color.White.copy(alpha = 0.9f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("start_label")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "TAP ANYWHERE TO PLAY",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        if (settings.isReverseMode) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "REVERSE MODE • TARGET ${formatMMSS(settings.reverseLimitMs)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF38BDF8),
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    MyGameState.RUNNING -> {
                        if (settings.isReverseMode) {
                            val elapsedSec = elapsedTime / 1000.0
                            val textValue = String.format("%.2f", elapsedSec)
                            Text(
                                text = textValue,
                                fontSize = 90.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-1).sp,
                                color = Color(0xFF38BDF8),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("countdown_timer_text")
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "TAP TO STACK TIME",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                                color = Color.White.copy(alpha = 0.95f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Taps: $round  •  Total: ${formatMMSS(stackedTime + elapsedTime)} / ${formatMMSS(settings.reverseLimitMs)}",
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        } else {
                            val remainingSec = remaining / 1000.0
                            val textValue = if (remaining >= 10000) {
                                String.format("%.1f", remainingSec)
                            } else {
                                String.format("%.2f", remainingSec)
                            }
                            Text(
                                text = textValue,
                                fontSize = 90.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-1).sp,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("countdown_timer_text")
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = if (isUrgent) "PRESS NOW!" else "TAP ANYWHERE TO RESET",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp,
                                color = Color.White.copy(alpha = 0.95f),
                                textAlign = TextAlign.Center
                            )
                            if (settings.livesEnabled) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Leben: $remainingLives / ${settings.livesCount}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White.copy(alpha = 0.82f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("lives_text")
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = nextDifficultyText(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.72f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("next_difficulty_text")
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Round $round  •  limit ${String.format("%.2fs", countdown / 1000.0)}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    MyGameState.FAILED -> {
                        Text(
                            text = "FAILED",
                            fontSize = 62.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1.5).sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("failed_label")
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "TAP ANYWHERE TO RESTART",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = Color.White.copy(alpha = 0.75f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.25f))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "Survived ${round - 1} round${if (round - 1 == 1) "" else "s"}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag("survived_stats")
                            )
                        }
                        if (settings.highScore > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "🏆 High Score: ${settings.highScore}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD700),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    MyGameState.FINISHED -> {
                        Text(
                            text = "COMPLETE!",
                            fontSize = 58.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1).sp,
                            color = Color(0xFFFBBF24),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag("finished_label")
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "TAP ANYWHERE TO PLAY AGAIN",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.3f))
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Total stacked: ${round} taps",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "Target limit: ${formatMMSS(settings.reverseLimitMs)}",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(alpha = 0.7f),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        if (settings.reverseHighScore > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "🏆 Best Taps: ${settings.reverseHighScore}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFFD700),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    } else {
        // --- Standard (Non-fullscreen) Game Panel with Progress Ring ---
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        // Shaking animation setup for failed state
        var shakeOffset by remember { mutableStateOf(0f) }
        LaunchedEffect(gameState) {
            if (gameState == MyGameState.FAILED) {
                // simple tactile shake
                val shakeSequence = listOf(-15f, 15f, -12f, 12f, -8f, 8f, -4f, 4f, 0f)
                for (offset in shakeSequence) {
                    shakeOffset = offset
                    delay(40)
                }
            } else {
                shakeOffset = 0f
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { viewModel.handlePress() }
                )
                .testTag("standard_game_panel"),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(330.dp)
                        .graphicsLayer(
                            translationX = shakeOffset
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    // Outer Circular Progress Ring Canvas (perfectly overlaying the layout)
                    Canvas(modifier = Modifier.size(310.dp)) {
                        val strokeWidth = 10f
                        // background light-grey track
                        drawCircle(
                            color = Color(0x0D000000), // 5% black representation of original border
                            radius = size.minDimension / 2f - strokeWidth,
                            style = Stroke(width = strokeWidth)
                        )
                        if (successPulse > 0f) {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.55f * successPulse),
                                radius = size.minDimension / 2f - strokeWidth,
                                style = Stroke(width = strokeWidth + (18f * successPulse), cap = StrokeCap.Round)
                            )
                        }
                        if (gameState == MyGameState.RUNNING) {
                            if (settings.isReverseMode && settings.reverseLimitMs > 0) {
                                val totalElapsed = stackedTime + elapsedTime
                                val sweepAngle = 360f * (totalElapsed.toFloat() / settings.reverseLimitMs).coerceIn(0f, 1f)
                                drawArc(
                                    color = Color(0xFF0284C7), // Sky blue progress track
                                    startAngle = -90f,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth + 4f, cap = StrokeCap.Round)
                                )
                            } else if (countdown > 0) {
                                val sweepAngle = 360f * (remaining.toFloat() / countdown)
                                drawArc(
                                    color = if (isUrgent) Color(0xFFEF4444) else Color(0xFF10B981),
                                    startAngle = -90f,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth + 4f, cap = StrokeCap.Round)
                                )
                            }
                        }
                    }

                    // Tactile Central Round Button Layout
                    val buttonBaseModifier = Modifier
                        .size(270.dp)
                        .clip(CircleShape)
                    
                    val buttonBackground = when (gameState) {
                        MyGameState.IDLE -> Color(0xFF111827) // gray-900 black tone
                        MyGameState.RUNNING -> {
                            if (settings.isReverseMode) Color(0xFF0284C7) else if (isUrgent) Color(0xFFEF4444) else Color(0xFF059669)
                        }
                        MyGameState.FAILED -> Color(0xFFB91C1C) // red-700
                        MyGameState.FINISHED -> Color(0xFFD97706) // amber complete status
                    }

                    Box(
                        modifier = buttonBaseModifier
                            .background(buttonBackground)
                            .graphicsLayer(
                                scaleX = (if (isUrgent) pulseScale else 1.0f) + (successPulse * 0.08f),
                                scaleY = (if (isUrgent) pulseScale else 1.0f) + (successPulse * 0.08f)
                            )
                            .testTag("tactile_game_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(20.dp)
                        ) {
                            when (gameState) {
                                MyGameState.IDLE -> {
                                    Text(
                                        text = "START",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    if (settings.isReverseMode) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "reverse mode",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Light,
                                            color = Color.White.copy(alpha = 0.7f),
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                                MyGameState.RUNNING -> {
                                    if (settings.isReverseMode) {
                                        val elapsedSec = elapsedTime / 1000.0
                                        val textValue = String.format("%.2f", elapsedSec)
                                        Text(
                                            text = textValue,
                                            fontSize = 58.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.testTag("countdown_timer_text")
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "press to stack",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp,
                                            color = Color.White.copy(alpha = 0.85f),
                                            textAlign = TextAlign.Center
                                        )
                                    } else {
                                        val remainingSec = remaining / 1000.0
                                        val textValue = if (remaining >= 10000) {
                                            String.format("%.1f", remainingSec)
                                        } else {
                                            String.format("%.2f", remainingSec)
                                        }
                                        Text(
                                            text = textValue,
                                            fontSize = 58.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.testTag("countdown_timer_text")
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (isUrgent) "PRESS NOW!" else "press to reset",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp,
                                            color = Color.White.copy(alpha = 0.85f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                MyGameState.FAILED -> {
                                    Text(
                                        text = "FAILED",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "tap to restart",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.85f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                                MyGameState.FINISHED -> {
                                    Text(
                                        text = "COMPLETE!",
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "tap to replay",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White.copy(alpha = 0.85f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                // Stats footer readout below the circle
                Spacer(modifier = Modifier.height(16.dp))
                if (settings.livesEnabled) {
                    Text(
                        text = "Leben: $remainingLives / ${settings.livesCount}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF111827),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("lives_text")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Text(
                    text = nextDifficultyText(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B7280),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("next_difficulty_text")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.height(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (gameState) {
                        MyGameState.RUNNING -> {
                            if (settings.isReverseMode) {
                                Text(
                                    text = "Taps: $round  •  Total: ${formatMMSS(stackedTime + elapsedTime)} / ${formatMMSS(settings.reverseLimitMs)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF9CA3AF),
                                    modifier = Modifier.testTag("running_stats")
                                )
                            } else {
                                Text(
                                    text = "Round $round  •  limit ${String.format("%.2fs", countdown / 1000.0)}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF9CA3AF),
                                    modifier = Modifier.testTag("running_stats")
                                )
                            }
                        }
                        MyGameState.FAILED -> {
                            Text(
                                text = "Survived ${round - 1} round${if (round - 1 == 1) "" else "s"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF9CA3AF),
                                modifier = Modifier.testTag("failures_stats")
                            )
                        }
                        MyGameState.FINISHED -> {
                            Text(
                                text = "Achieved ${round} taps in ${formatMMSS(settings.reverseLimitMs)}!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD97706),
                                modifier = Modifier.testTag("failures_stats")
                            )
                        }
                        else -> {
                            val displayHighScore = if (settings.isReverseMode) settings.reverseHighScore else settings.highScore
                            if (displayHighScore > 0) {
                                Text(
                                    text = if (settings.isReverseMode) {
                                        "🏆 Best Stopwatch Run: $displayHighScore taps"
                                    } else {
                                        "🏆 High Score: $displayHighScore rounds"
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD97706)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DisplayTabContent(viewModel: MainViewModel) {
    val statuses by viewModel.displayLifeStatuses.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030712))
            .testTag("display_lives_screen")
    ) {
        if (statuses.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "CONNECTING TO CONTROLLER\nWAITING FOR FOLLOWER LIVES",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.5.sp
                )
            }
        } else {
            statuses.forEach { status ->
                DisplayLifePanel(
                    status = status,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
fun DisplayLifePanel(
    status: DisplayLifeStatus,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = status.label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        Spacer(modifier = Modifier.height(26.dp))
        val icons = status.maxLives.coerceIn(1, 20)
        val filled = status.lives.coerceIn(0, icons)
        Text(
            text = buildString {
                repeat(filled) { append("●") }
                repeat(icons - filled) { append("○") }
            },
            fontSize = 46.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center,
            lineHeight = 52.sp
        )
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = "${status.lives} / ${status.maxLives}",
            fontSize = 52.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GameSettingsTabContent(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val nearbyStatus by viewModel.nearbyStatus.collectAsStateWithLifecycle()
    val connectedDevicesCount by viewModel.connectedDevicesCount.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF9FAFB))
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag("controller_game_settings_screen")
    ) {
        Text(
            text = "Game Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
            letterSpacing = (-0.5).sp
        )
        Text(
            text = "Controller mode is active. Timer settings mirror to connected followers.",
            fontSize = 13.sp,
            color = Color(0xFF6B7280),
            modifier = Modifier.padding(top = 2.dp, bottom = 18.dp)
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(16.dp),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color(0xFFE5E7EB)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Controller Flow",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Connected followers: $connectedDevicesCount • $nearbyStatus",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280),
                    lineHeight = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        viewModel.broadcastOpenGameTab()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("switch_followers_to_game_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF111827),
                        contentColor = Color.White
                    )
                ) {
                    Text("Switch all followers to Game")
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.broadcastResetGame() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("reset_all_devices_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Reset all devices to Start")
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        GameTimerSettingsCard(viewModel = viewModel)

        Spacer(modifier = Modifier.height(20.dp))
        SettingsCategory(title = "Layout Options") {
            SettingsToggleRow(
                label = "Fullscreen Mode",
                description = "Edge-to-edge immersive view on the Game tab",
                checked = settings.fullscreen,
                onCheckedChange = { viewModel.toggleFullscreen() },
                modifier = Modifier.testTag("fullscreen_toggle_row_controller")
            )
        }
    }
}

@Composable
fun GameTimerSettingsCard(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var scaleIntervalInput by remember(settings.scaleInterval) { mutableStateOf(settings.scaleInterval.toString()) }

    fun formatMMSS(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    SettingsCategory(title = "Game Settings") {
        SettingsToggleRow(
            label = "Reverse Mode (Stopwatch)",
            description = "Clicking a running stopwatch stacks times up to a limit",
            checked = settings.isReverseMode,
            onCheckedChange = { viewModel.toggleReverseMode() },
            modifier = Modifier.testTag("reverse_game_mode_toggle")
        )
        if (settings.isReverseMode) {
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            SettingsAdjusterRow(
                label = "Stopwatch Target Limit",
                description = "Stack elapsed times up to this total limit",
                valueText = formatMMSS(settings.reverseLimitMs),
                onMinus = { viewModel.adjustReverseLimitMs(-30000L) },
                onPlus = { viewModel.adjustReverseLimitMs(30000L) },
                modifier = Modifier.testTag("reverse_limit_adjuster")
            )
        }
        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
        SettingsToggleRow(
            label = "Lives",
            description = "Timeouts remove one life before the game fails",
            checked = settings.livesEnabled,
            onCheckedChange = { viewModel.toggleLivesEnabled() },
            modifier = Modifier.testTag("lives_toggle_row")
        )
        if (settings.livesEnabled) {
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            SettingsAdjusterRow(
                label = "Life count",
                description = "How many timeouts are allowed per run",
                valueText = settings.livesCount.toString(),
                onMinus = { viewModel.adjustLivesCount(-1) },
                onPlus = { viewModel.adjustLivesCount(1) },
                modifier = Modifier.testTag("lives_count_adjuster")
            )
        }
        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
        SettingsToggleRow(
            label = "Auto difficulty scaling",
            description = if (settings.autoDifficultyEnabled) {
                "Auto mode decreases the limit after each interval"
            } else {
                "Manual mode is active. Use the limit buttons below."
            },
            checked = settings.autoDifficultyEnabled,
            onCheckedChange = { viewModel.toggleAutoDifficulty() },
            modifier = Modifier.testTag("auto_difficulty_toggle")
        )

        if (settings.autoDifficultyEnabled) {
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            SettingsAdjusterRow(
                label = "Initial countdown",
                description = "Starting limit before auto scaling reduces it",
                valueText = "${settings.initTime}ms",
                onMinus = { viewModel.adjustInitTime(-500) },
                onPlus = { viewModel.adjustInitTime(500) },
                modifier = Modifier.testTag("init_time_adjuster")
            )
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            SettingsAdjusterRow(
                label = "Reduction per interval",
                description = "How much the limit shrinks each interval",
                valueText = "${settings.reduction}ms",
                onMinus = { viewModel.adjustReduction(-50) },
                onPlus = { viewModel.adjustReduction(50) },
                modifier = Modifier.testTag("reduction_adjuster")
            )
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .testTag("scale_interval_input_row")
            ) {
                Text(
                    text = "Scale interval",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937)
                )
                Text(
                    text = "Reduction interval in exact milliseconds",
                    fontSize = 12.sp,
                    color = Color(0xFF9CA3AF),
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                OutlinedTextField(
                    value = scaleIntervalInput,
                    onValueChange = { raw ->
                        val cleaned = raw.filter { it.isDigit() }.take(7)
                        scaleIntervalInput = cleaned
                        cleaned.toIntOrNull()?.let { viewModel.setScaleIntervalMs(it) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scale_interval_ms_input"),
                    singleLine = true,
                    suffix = { Text("ms") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        } else {
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            SettingsAdjusterRow(
                label = "Manual countdown limit",
                description = "Use -/+ to directly decrease or increase the active limit",
                valueText = "${settings.initTime}ms",
                onMinus = { viewModel.adjustManualLimit(-1) },
                onPlus = { viewModel.adjustManualLimit(1) },
                modifier = Modifier.testTag("manual_limit_adjuster")
            )
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            SettingsAdjusterRow(
                label = "Manual step",
                description = "Amount used by the manual limit -/+ buttons",
                valueText = "${settings.manualLimitStepMs}ms",
                onMinus = { viewModel.adjustManualLimitStep(-50) },
                onPlus = { viewModel.adjustManualLimitStep(50) },
                modifier = Modifier.testTag("manual_limit_step_adjuster")
            )
        }

        HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
        SettingsAdjusterRow(
            label = "Urgent threshold",
            description = "Button turns red when time remaining ≤ this",
            valueText = "${settings.urgentMs}ms",
            onMinus = { viewModel.adjustUrgentThreshold(-250) },
            onPlus = { viewModel.adjustUrgentThreshold(250) },
            modifier = Modifier.testTag("urgent_threshold_adjuster")
        )
    }
}

@Composable
fun SettingsTabContent(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val nearbyStatus by viewModel.nearbyStatus.collectAsStateWithLifecycle()
    val connectedDevicesCount by viewModel.connectedDevicesCount.collectAsStateWithLifecycle()
    val availableControllers by viewModel.availableControllers.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context.findActivity()
    val scope = rememberCoroutineScope()
    var isCheckingForUpdate by remember { mutableStateOf(false) }
    var isInstallingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<GitHubReleaseUpdater.UpdateInfo?>(null) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }
    var showInstallPrompt by remember { mutableStateOf(false) }
    fun startNearbyForCurrentRole() {
        if (settings.isController) {
            viewModel.startNearbyHosting()
        } else {
            viewModel.startNearbyDiscovering()
        }
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasNearbyRuntimePermissions(context)) {
            startNearbyForCurrentRole()
        }
    }

    LaunchedEffect(settings.isController) {
        val missingPermissions = missingNearbyRuntimePermissions(context)
        if (missingPermissions.isEmpty()) {
            startNearbyForCurrentRole()
        } else {
            launcher.launch(missingPermissions)
        }
    }

    fun formatMMSS(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%d:%02d", minutes, remainingSeconds)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF9FAFB)) // Faint slate-grey body
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag("settings_screen")
    ) {
        Text(
            text = "Settings",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF111827),
            letterSpacing = (-0.5).sp
        )
        Text(
            text = "Configure the reaction game to your liking!",
            fontSize = 13.sp,
            color = Color(0xFF6B7280),
            modifier = Modifier.padding(top = 2.dp, bottom = 24.dp)
        )

        // Category: Nearby Controllers Mirror setup
        SettingsCategory(title = "Nearby Controller Setup") {
            SettingsToggleRow(
                label = "Enable Controller Mode",
                description = "Broadcasting role as host to mirror settings to nearby followers",
                checked = settings.isController,
                onCheckedChange = { viewModel.toggleController() },
                modifier = Modifier.testTag("nearby_controller_toggle")
            )
            HorizontalDivider(color = Color(0xFFF3F4F6), thickness = 1.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (connectedDevicesCount > 0) Color(0xFF10B981) else Color(0xFFF59E0B))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Status: $nearbyStatus",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1F2937)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (settings.isController) {
                        "Controller Mode: $connectedDevicesCount follower device(s) connected"
                    } else {
                        "Follow Mode: $connectedDevicesCount controller connection(s) active"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6B7280)
                )
                Spacer(modifier = Modifier.height(10.dp))
                if (settings.isController) {
                    Text(
                        text = "Ablauf: Controller Mode aktivieren → diese App wirbt als Host → bekannte Follow-Geräte verbinden automatisch. Button bleibt nur als Fallback.",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280),
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            val missingPermissions = missingNearbyRuntimePermissions(context)
                            if (missingPermissions.isEmpty()) {
                                viewModel.startNearbyHosting()
                            } else {
                                launcher.launch(missingPermissions)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("nearby_controller_restart_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Restart Controller Hosting")
                    }
                } else {
                    Text(
                        text = "Ablauf: Controller einschalten → dieses Follow-Gerät scannt und verbindet automatisch. Scan/Refresh und Connect bleiben als manuelle Fallbacks.",
                        fontSize = 11.sp,
                        color = Color(0xFF6B7280),
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val missingPermissions = missingNearbyRuntimePermissions(context)
                            if (missingPermissions.isEmpty()) {
                                viewModel.startNearbyDiscovering()
                            } else {
                                launcher.launch(missingPermissions)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .testTag("nearby_follow_scan_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF111827),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Scan / Refresh Controllers")
                    }
                    if (availableControllers.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Noch kein Controller gefunden. Bluetooth, WLAN und Standort am Gerät aktiv lassen; Nearby braucht das je nach Android-Version.",
                            fontSize = 11.sp,
                            color = Color(0xFF9CA3AF),
                            lineHeight = 15.sp
                        )
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                        availableControllers.forEach { controller ->
                            OutlinedButton(
                                onClick = { viewModel.connectToController(controller.id, controller.name) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                                    .testTag("nearby_follow_connect_button_${controller.id}"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Connect: ${controller.name}")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        GameTimerSettingsCard(viewModel = viewModel)

        Spacer(modifier = Modifier.height(20.dp))

        // Category 1: Layout Options
        SettingsCategory(title = "Layout Options") {
            SettingsToggleRow(
                label = "Fullscreen Mode",
                description = "Edge-to-edge immersive view on the Game tab",
                checked = settings.fullscreen,
                onCheckedChange = { viewModel.toggleFullscreen() },
                modifier = Modifier.testTag("fullscreen_toggle_row")
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        SettingsCategory(title = "App Updates") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Current version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 12.sp,
                    color = Color(0xFF6B7280)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (isCheckingForUpdate || isInstallingUpdate) return@Button
                        scope.launch {
                            isCheckingForUpdate = true
                            updateStatusMessage = null
                            val result = GitHubReleaseUpdater.checkForUpdate()
                            isCheckingForUpdate = false
                            result.fold(
                                onSuccess = { info ->
                                    if (info == null) {
                                        updateInfo = null
                                        updateStatusMessage = "Already on latest version."
                                    } else {
                                        updateInfo = info
                                        showInstallPrompt = true
                                    }
                                },
                                onFailure = { error ->
                                    updateInfo = null
                                    val message = error.message ?: "Unknown error"
                                    updateStatusMessage = if (message == "No GitHub release published yet.") {
                                        "No published update release yet."
                                    } else {
                                        "Update check failed: $message"
                                    }
                                }
                            )
                        }
                    },
                    enabled = !isCheckingForUpdate && !isInstallingUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("check_updates_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF111827),
                        contentColor = Color.White
                    )
                ) {
                    if (isCheckingForUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Text("Check for updates")
                    }
                }

                if (isInstallingUpdate) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Downloading update...",
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280)
                    )
                }

                updateStatusMessage?.let { message ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        color = Color(0xFF6B7280)
                    )
                }
            }
        }

        if (showInstallPrompt && updateInfo != null) {
            val info = updateInfo!!
            AlertDialog(
                onDismissRequest = { showInstallPrompt = false },
                title = { Text("Update available") },
                text = {
                    Column {
                        Text("Version ${info.versionName} is available.")
                        if (info.body.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = info.body,
                                fontSize = 12.sp,
                                color = Color(0xFF6B7280)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showInstallPrompt = false
                            val currentActivity = activity
                            if (currentActivity == null) {
                                updateStatusMessage = "Cannot install update from this context."
                                return@TextButton
                            }
                            scope.launch {
                                isInstallingUpdate = true
                                updateStatusMessage = null
                                val installResult =
                                    GitHubReleaseUpdater.downloadAndInstall(currentActivity, info)
                                isInstallingUpdate = false
                                installResult.fold(
                                    onSuccess = {
                                        updateStatusMessage = "Installer opened."
                                    },
                                    onFailure = { error ->
                                        updateStatusMessage =
                                            "Update install failed: ${error.message ?: "Unknown error"}"
                                    }
                                )
                            }
                        },
                        enabled = !isInstallingUpdate
                    ) {
                        Text("Install")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showInstallPrompt = false }) {
                        Text("Later")
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(30.dp))

        // Reset settings operation button row
        Button(
            onClick = { viewModel.resetSettingsToDefault() },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF111827),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("reset_defaults_button"),
            contentPadding = PaddingValues()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset Icon",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Reset to defaults",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        // High score record badge under reset button inside scrollview supporting both modes
        if (settings.highScore > 0 || settings.reverseHighScore > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0x0FFFBF27)),
                shape = RoundedCornerShape(12.dp),
                border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color(0x35D97706)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "🏆", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Best Session Records",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFB45309)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (settings.highScore > 0) {
                        Text(
                            text = "• Standard Countdown: ${settings.highScore} consecutive rounds successfully survived!",
                            fontSize = 12.sp,
                            color = Color(0xFFB45309)
                        )
                    }
                    if (settings.reverseHighScore > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Reverse Stopwatch: ${settings.reverseHighScore} total taps completed in 5+ min sessions!",
                            fontSize = 12.sp,
                            color = Color(0xFFB45309)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCategory(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Color(0xFFE5E7EB))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF9FAFB))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = title.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
            content()
        }
    }
}

@Composable
fun SettingsToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937)
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF111827),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFE5E7EB)
            )
        )
    }
}

@Composable
fun SettingsAdjusterRow(
    label: String,
    description: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2937)
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Color(0xFF9CA3AF),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = onMinus,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6))
                    .testTag("btn_minus"),
            ) {
                Icon(
                    imageVector = RemoveIcon,
                    contentDescription = "Minus",
                    tint = Color(0xFF1F2937),
                    modifier = Modifier.size(12.dp)
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .width(66.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = valueText,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1F2937),
                    textAlign = TextAlign.Center
                )
            }
            IconButton(
                onClick = onPlus,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF3F4F6))
                    .testTag("btn_plus"),
            ) {
                Icon(
                    imageVector = AddIcon,
                    contentDescription = "Plus",
                    tint = Color(0xFF1F2937),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
