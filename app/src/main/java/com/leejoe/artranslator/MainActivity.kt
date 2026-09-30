package com.leejoe.artranslator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var overlay: TranslationOverlayView
    private lateinit var statusView: TextView

    @Volatile private var config = AppConfig(
        "http://192.168.31.31:1234/v1/responses",
        "google/gemma-4-12b-qat",
        "Simplified Chinese"
    )

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else status("Camera permission denied")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        loadConfig()
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))
        overlay = TranslationOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xaa000000.toInt())
        }
        val endpointEdit = EditText(this).apply {
            setText(config.endpoint)
            hint = "http://192.168.31.31:1234/v1/responses"
            setTextColor(Color.WHITE)
            setHintTextColor(0xffaaaaaa.toInt())
            textSize = 12f
            isSingleLine = true
        }
        panel.addView(endpointEdit, LinearLayout.LayoutParams(-1, dp(40)))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val models = listOf("google/gemma-4-12b-qat", "google/gemma-4-e4b")
        val modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, models)
            setSelection(models.indexOf(config.model).coerceAtLeast(0))
        }
        row.addView(modelSpinner, LinearLayout.LayoutParams(0, dp(44), 1.35f))

        val targetEdit = EditText(this).apply {
            setText(config.targetLanguage)
            hint = "Target language"
            setTextColor(Color.WHITE)
            setHintTextColor(0xffaaaaaa.toInt())
            textSize = 12f
            isSingleLine = true
        }
        row.addView(targetEdit, LinearLayout.LayoutParams(0, dp(44), 0.9f))

        val apply = Button(this).apply {
            text = "Apply"
            textSize = 11f
            setOnClickListener {
                config = AppConfig(
                    endpointEdit.text.toString().trim(),
                    models[modelSpinner.selectedItemPosition],
                    targetEdit.text.toString().trim().ifBlank { "Simplified Chinese" }
                )
                saveConfig()
                status("Using ${config.model} @ ${config.normalizedEndpoint()}")
            }
        }
        row.addView(apply, LinearLayout.LayoutParams(dp(72), dp(44)))
        panel.addView(row, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
            textSize = 11f
            text = "Ready"
        }
        root.addView(statusView, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analyzer = CameraFrameAnalyzer(
                recognizer, mainScope, { config },
                { width, height, regions -> overlay.post { overlay.setRegions(width, height, regions) } },
                { message -> status(message) }
            )
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                status("Camera running - detector about 4 FPS")
            } catch (t: Throwable) {
                status("Camera error: ${t.message ?: t.javaClass.simpleName}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun status(message: String) {
        runOnUiThread { if (::statusView.isInitialized) statusView.text = message }
    }

    private fun loadConfig() {
        val p = getSharedPreferences("ar_translator", MODE_PRIVATE)
        config = AppConfig(
            p.getString("endpoint", config.endpoint) ?: config.endpoint,
            p.getString("model", config.model) ?: config.model,
            p.getString("targetLanguage", config.targetLanguage) ?: config.targetLanguage
        )
    }

    private fun saveConfig() {
        getSharedPreferences("ar_translator", MODE_PRIVATE).edit()
            .putString("endpoint", config.endpoint)
            .putString("model", config.model)
            .putString("targetLanguage", config.targetLanguage)
            .apply()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        cameraExecutor.shutdown()
        mainScope.cancel()
    }
}
