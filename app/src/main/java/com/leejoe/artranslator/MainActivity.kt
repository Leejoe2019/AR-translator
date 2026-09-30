package com.leejoe.artranslator

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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

class MainActivity :
    AppCompatActivity() {

    private val mainScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main.immediate
        )

    private lateinit var cameraExecutor:
        ExecutorService

    private lateinit var previewView:
        PreviewView

    private lateinit var overlay:
        TranslationOverlayView

    private lateinit var statusView:
        TextView

    private lateinit var llmView:
        TextView

    private lateinit var roiPreview:
        ImageView

    private val llmText =
        StringBuilder()

    private var debugBitmap:
        Bitmap? = null

    @Volatile
    private var config =
        AppConfig(
            endpoint =
                "http://192.168.31.31:1234/api/v1/chat",
            model =
                "google/gemma-4-12b-qat",
            targetLanguage =
                "Simplified Chinese"
        )

    private val recognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions
                .DEFAULT_OPTIONS
        )
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts
                .RequestPermission()
        ) { granted ->
            if (granted) {
                startCamera()
            } else {
                status(
                    "Camera permission denied"
                )
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        cameraExecutor =
            Executors
                .newSingleThreadExecutor()

        loadConfig()
        buildUi()

        if (
            ContextCompat
                .checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) ==
            PackageManager
                .PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(
                Manifest.permission.CAMERA
            )
        }
    }

    private fun buildUi() {
        val root =
            FrameLayout(this)

        previewView =
            PreviewView(this).apply {
                scaleType =
                    PreviewView
                        .ScaleType
                        .FILL_CENTER

                implementationMode =
                    PreviewView
                        .ImplementationMode
                        .PERFORMANCE
            }

        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        overlay =
            TranslationOverlayView(this)

        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                -1,
                -1
            )
        )

        val topPanel =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(8),
                    dp(6),
                    dp(8),
                    dp(6)
                )

                setBackgroundColor(
                    0xaa000000.toInt()
                )
            }

        val endpointEdit =
            EditText(this).apply {
                setText(config.endpoint)

                hint =
                    "http://192.168.31.31:1234/api/v1/chat"

                setTextColor(
                    Color.WHITE
                )

                setHintTextColor(
                    0xffaaaaaa.toInt()
                )

                textSize = 12f
                isSingleLine = true
            }

        topPanel.addView(
            endpointEdit,
            LinearLayout.LayoutParams(
                -1,
                dp(40)
            )
        )

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL
            }

        val models =
            listOf(
                "google/gemma-4-12b-qat",
                "google/gemma-4-e4b"
            )

        val modelSpinner =
            Spinner(this).apply {
                adapter =
                    ArrayAdapter(
                        this@MainActivity,
                        android.R.layout
                            .simple_spinner_dropdown_item,
                        models
                    )

                setSelection(
                    models
                        .indexOf(
                            config.model
                        )
                        .coerceAtLeast(0)
                )
            }

        row.addView(
            modelSpinner,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                1.35f
            )
        )

        val targetEdit =
            EditText(this).apply {
                setText(
                    config.targetLanguage
                )

                hint =
                    "Target language"

                setTextColor(
                    Color.WHITE
                )

                setHintTextColor(
                    0xffaaaaaa.toInt()
                )

                textSize = 12f
                isSingleLine = true
            }

        row.addView(
            targetEdit,
            LinearLayout.LayoutParams(
                0,
                dp(44),
                0.9f
            )
        )

        val apply =
            Button(this).apply {
                text = "Apply"
                textSize = 11f

                setOnClickListener {
                    config =
                        AppConfig(
                            endpoint =
                                endpointEdit
                                    .text
                                    .toString()
                                    .trim(),
                            model =
                                models[
                                    modelSpinner
                                        .selectedItemPosition
                                ],
                            targetLanguage =
                                targetEdit
                                    .text
                                    .toString()
                                    .trim()
                                    .ifBlank {
                                        "Simplified Chinese"
                                    }
                        )

                    saveConfig()

                    status(
                        "Using \${config.model} @ " +
                            config
                                .nativeChatEndpoint()
                    )
                }
            }

        row.addView(
            apply,
            LinearLayout.LayoutParams(
                dp(72),
                dp(44)
            )
        )

        topPanel.addView(
            row,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT
            )
        )

        root.addView(
            topPanel,
            FrameLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val debugPanel =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    0xaa000000.toInt()
                )

                setPadding(
                    dp(6),
                    dp(4),
                    dp(6),
                    dp(4)
                )
            }

        val debugRow =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.BOTTOM
            }

        roiPreview =
            ImageView(this).apply {
                scaleType =
                    ImageView
                        .ScaleType
                        .FIT_CENTER

                setBackgroundColor(
                    0xff202020.toInt()
                )

                contentDescription =
                    "Last ROI sent to Vision LLM"
            }

        debugRow.addView(
            roiPreview,
            LinearLayout.LayoutParams(
                dp(135),
                dp(90)
            )
        )

        llmView =
            TextView(this).apply {
                setTextColor(
                    0xffb9f6ca.toInt()
                )

                textSize = 11f

                setPadding(
                    dp(7),
                    0,
                    0,
                    0
                )

                maxLines = 7

                text =
                    "LLM stream will appear here"
            }

        debugRow.addView(
            llmView,
            LinearLayout.LayoutParams(
                0,
                dp(90),
                1f
            )
        )

        debugPanel.addView(
            debugRow,
            LinearLayout.LayoutParams(
                -1,
                dp(90)
            )
        )

        statusView =
            TextView(this).apply {
                setTextColor(
                    Color.WHITE
                )

                setPadding(
                    dp(2),
                    dp(4),
                    dp(2),
                    dp(2)
                )

                textSize = 10f
                text = "Ready"
            }

        debugPanel.addView(
            statusView,
            LinearLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT
            )
        )

        root.addView(
            debugPanel,
            FrameLayout.LayoutParams(
                -1,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(root)
    }

    private fun startCamera() {
        val future =
            ProcessCameraProvider
                .getInstance(this)

        future.addListener({
            val provider =
                future.get()

            val preview =
                Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            previewView
                                .surfaceProvider
                        )
                    }

            val analyzer =
                CameraFrameAnalyzer(
                    recognizer =
                        recognizer,
                    scope =
                        mainScope,
                    configProvider = {
                        config
                    },
                    onOverlay = {
                        width,
                        height,
                        regions ->

                        overlay.post {
                            overlay.setRegions(
                                width,
                                height,
                                regions
                            )
                        }
                    },
                    onStatus =
                        ::status,
                    onLlmReset =
                        ::llmReset,
                    onLlmDelta =
                        ::llmAppend,
                    onRoiPreview =
                        ::showRoiPreview
                )

            val analysis =
                ImageAnalysis.Builder()
                    .setTargetResolution(
                        Size(
                            1280,
                            720
                        )
                    )
                    .setBackpressureStrategy(
                        ImageAnalysis
                            .STRATEGY_KEEP_ONLY_LATEST
                    )
                    .build()
                    .also {
                        it.setAnalyzer(
                            cameraExecutor,
                            analyzer
                        )
                    }

            try {
                provider.unbindAll()

                provider.bindToLifecycle(
                    this,
                    CameraSelector
                        .DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                status(
                    "RED=detected " +
                        "YELLOW=LLM " +
                        "GREEN=translated"
                )
            } catch (t: Throwable) {
                status(
                    "Camera error: " +
                        (
                            t.message
                                ?: t.javaClass
                                    .simpleName
                        )
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun status(
        message: String
    ) {
        runOnUiThread {
            if (
                ::statusView
                    .isInitialized
            ) {
                statusView.text =
                    message
            }
        }
    }

    private fun llmReset(
        header: String
    ) {
        runOnUiThread {
            llmText.clear()
            llmText
                .append(header)
                .append('\n')

            if (
                ::llmView.isInitialized
            ) {
                llmView.text =
                    llmText.toString()
            }
        }
    }

    private fun llmAppend(
        fragment: String
    ) {
        runOnUiThread {
            llmText.append(fragment)

            if (
                llmText.length > 1800
            ) {
                llmText.delete(
                    0,
                    llmText.length -
                        1800
                )
            }

            if (
                ::llmView.isInitialized
            ) {
                llmView.text =
                    llmText.toString()
            }
        }
    }

    private fun showRoiPreview(
        bitmap: Bitmap
    ) {
        runOnUiThread {
            debugBitmap
                ?.takeIf {
                    it !== bitmap &&
                        !it.isRecycled
                }
                ?.recycle()

            debugBitmap = bitmap

            if (
                ::roiPreview
                    .isInitialized
            ) {
                roiPreview
                    .setImageBitmap(
                        bitmap
                    )
            }
        }
    }

    private fun loadConfig() {
        val prefs =
            getSharedPreferences(
                "ar_translator",
                MODE_PRIVATE
            )

        config =
            AppConfig(
                endpoint =
                    prefs.getString(
                        "endpoint",
                        config.endpoint
                    ) ?: config.endpoint,
                model =
                    prefs.getString(
                        "model",
                        config.model
                    ) ?: config.model,
                targetLanguage =
                    prefs.getString(
                        "targetLanguage",
                        config.targetLanguage
                    ) ?: config.targetLanguage
            )
    }

    private fun saveConfig() {
        getSharedPreferences(
            "ar_translator",
            MODE_PRIVATE
        )
            .edit()
            .putString(
                "endpoint",
                config.endpoint
            )
            .putString(
                "model",
                config.model
            )
            .putString(
                "targetLanguage",
                config.targetLanguage
            )
            .apply()
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density
        ).toInt()

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        cameraExecutor.shutdown()
        mainScope.cancel()

        debugBitmap
            ?.takeIf {
                !it.isRecycled
            }
            ?.recycle()
    }
}
