package com.example.alertdrive

import android.Manifest
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.alertdrive.ui.theme.AlertDriveTheme
import kotlinx.coroutines.*

sealed class Screen {
    object Login : Screen()
    object Menu : Screen()
    object Detection : Screen()
}

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prepare alarm sound (ensure alarm.mp3 is placed in res/raw)
        mediaPlayer = MediaPlayer.create(this, R.raw.alarm)
        mediaPlayer?.setLooping(true)

        // Request permissions
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.INTERNET), 0)

        setContent {
            AlertDriveTheme {
                var screen by remember { mutableStateOf<Screen>(Screen.Login) }
                var resultText by remember { mutableStateOf("Waiting for prediction...") }
                var errorText by remember { mutableStateOf("") }
                var features by remember { mutableStateOf<List<Float>>(emptyList()) }
                var isAuto by remember { mutableStateOf(false) }

                // Track drowsy timing
                var drowsyStartTime by remember { mutableStateOf<Long?>(null) }

                val throttleInterval = 500L
                var lastSentTime by remember { mutableStateOf(0L) }
                val context = LocalContext.current
                val lifecycleOwner = LocalLifecycleOwner.current

                val preview = remember { Preview.Builder().build() }
                val imageCapture = remember {
                    ImageCapture.Builder()
                        .setTargetRotation(windowManager.defaultDisplay.rotation)
                        .build()
                }
                val imageAnalysis = remember {
                    ImageAnalysis.Builder()
                        .setTargetRotation(windowManager.defaultDisplay.rotation)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                }

                // Bind camera
                LaunchedEffect(preview, imageCapture, imageAnalysis) {
                    try {
                        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            imageCapture,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Camera init failed", e)
                        errorText = "Camera init error: ${e.message}"
                    }
                }

                // Set up analyzer
                DisposableEffect(imageAnalysis, isAuto) {
                    val scope = CoroutineScope(Dispatchers.IO + Job())
                    if (isAuto) {
                        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { imageProxy ->
                            val now = System.currentTimeMillis()
                            if (now - lastSentTime < throttleInterval) {
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            lastSentTime = now

                            val bmp = try {
                                imageProxy.toBitmap()
                            } catch (e: Exception) {
                                errorText = "Frame→Bitmap error: ${e.message}"
                                imageProxy.close()
                                return@setAnalyzer
                            }
                            imageProxy.close()

                            scope.launch {
                                delay(100)
                                try {
                                    val res = ApiClient.sendBitmapToServer(bmp)
                                    withContext(Dispatchers.Main) {
                                        if (res != null) {
                                            resultText = "${res.label} (${res.confidence.toInt()}%)"
                                            features = res.features
                                            errorText = ""
                                        } else {
                                            resultText = "Prediction failed"
                                            errorText = "No response"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        resultText = "Error occurred"
                                        errorText = e.message ?: e.toString()
                                    }
                                }
                            }
                        }
                    } else {
                        imageAnalysis.clearAnalyzer()
                    }
                    onDispose {
                        scope.cancel()
                        imageAnalysis.clearAnalyzer()
                    }
                }

                // Alarm control: reset immediately on any non-exact drowsy
                LaunchedEffect(resultText) {
                    // Extract just the label (before space)
                    val label = resultText.substringBefore(" ")
                    val isDrowsy = label.equals("Drowsy", ignoreCase = true)
                    val now = System.currentTimeMillis()

                    if (isDrowsy) {
                        if (drowsyStartTime == null) {
                            drowsyStartTime = now
                        } else if (now - drowsyStartTime!! > 5000) {
                            mediaPlayer?.start()
                        }
                    } else {
                        // Immediately reset on non-Drowsy
                        drowsyStartTime = null
                        mediaPlayer?.let {
                            if (it.isPlaying) {
                                it.pause()
                                it.seekTo(0)
                            }
                        }
                    }
                }

                // Navigation
                when (screen) {
                    is Screen.Login -> LoginScreen(onSignIn = { screen = Screen.Menu })
                    is Screen.Menu -> MenuScreen { isAuto = true; screen = Screen.Detection }
                    is Screen.Detection -> com.example.alertdrive.DetectionScreen(
                        preview, imageCapture, resultText, errorText, features,
                        onEndDrive = {
                            isAuto = false
                            screen = Screen.Menu
                            resultText = "Waiting for prediction..."
                            features = emptyList()
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}


@Composable
fun LoginScreen(onSignIn: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E3A8A), // Deep blue top
                        Color(0xFF0F172A)  // Dark blue bottom
                    )
                )
            )
    ) {
        // Decorative circles in background
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset((-100).dp, (-100).dp)
                .alpha(0.1f)
                .background(Color(0xFFFFC107), CircleShape)
        )

        Box(
            modifier = Modifier
                .size(200.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 50.dp, y = 50.dp)
                .alpha(0.1f)
                .background(Color(0xFFFFC107), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // App Logo/Icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .shadow(12.dp, CircleShape)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFFAB00),
                                Color(0xFFFFC107)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "DD",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 42.sp
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Driver Drowsiness",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "Detection System",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFFFC107), // Amber
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Sign In Button - Glass effect
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0x20FFFFFF) // Semi-transparent white
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Button(
                    onClick = onSignIn,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    elevation = null
                ) {
                    Text(
                        text = "Sign In",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Create Account Button
            Button(
                onClick = onSignIn, // Using same handler for simplicity
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFC107)
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(56.dp)
            ) {
                Text(
                    text = "Create Account",
                    color = Color(0xFF1E3A8A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
fun MenuScreen(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1E3A8A), // Deep blue top
                        Color(0xFF0F172A)  // Dark blue bottom
                    )
                )
            )
    ) {
        // Decorative elements
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset((-120).dp, (-120).dp)
                .alpha(0.1f)
                .background(Color(0xFFFFC107), CircleShape)
        )

        Box(
            modifier = Modifier
                .size(250.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 100.dp, y = 100.dp)
                .alpha(0.1f)
                .background(Color(0xFFFFC107), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Top app bar with profile
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Dashboard",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                // Profile icon - top right
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .shadow(4.dp, shape = CircleShape)
                        .background(Color(0xFFFFC107), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "P",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Welcome message
            Text(
                text = "Welcome Back",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Normal
            )

            Text(
                text = "Ready to drive safely?",
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFFFC107),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 48.dp)
            )

            // Stats cards
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Stat Card 1
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                        .aspectRatio(1f)
                        .shadow(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x30FFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "5",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Drives",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                // Stat Card 2
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                        .aspectRatio(1f)
                        .shadow(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0x30FFFFFF)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "98%",
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Alert Rate",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Start Drive Button
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107)),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "Start Drive",
                    color = Color(0xFF1E3A8A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
fun DetectionScreen(
    preview: Preview,
    imageCapture: ImageCapture,
    resultText: String,
    errorText: String,
    features: List<Float>,
    onEndDrive: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Camera preview with border
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .shadow(8.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
            ) {
                CameraPreviewView(
                    preview = preview,
                    modifier = Modifier.fillMaxSize()
                )

                // Status overlay at top
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x80000000))
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = resultText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (resultText.contains("Alert") || resultText.contains("Drowsy"))
                            Color(0xFFFF5252) else Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Bottom panel
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0x30FFFFFF)),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (errorText.isNotEmpty()) {
                        Text(
                            errorText,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    if (features.isNotEmpty()) {
                        Text(
                            "Detection Features",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 100.dp)
                        ) {
                            items(features) { f ->
                                Text(
                                    f.toString(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onEndDrive,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC107)),
                        shape = RoundedCornerShape(16.dp),
                        elevation = ButtonDefaults.buttonElevation(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Text(
                            "End Drive",
                            color = Color(0xFF1E3A8A),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}