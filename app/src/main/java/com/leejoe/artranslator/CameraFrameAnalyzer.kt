package com.leejoe.artranslator

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class CameraFrameAnalyzer(
    private val recognizer: TextRecognizer,
    private val scope: CoroutineScope,
    private val configProvider: () -> AppConfig,
    private val onOverlay:
        (Int, Int, List<OverlayRegion>) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onLlmReset: (String) -> Unit,
    private val onLlmDelta: (String) -> Unit,
    private val onRoiPreview: (Bitmap) -> Unit
) : ImageAnalysis.Analyzer {

    private data class Tracked(
        val id: Int,
        var rect: Rect,
        var hash: Long,
        var candidateHash: Long,
        var stableFrames: Int,
        var sentHash: Long?,
        var translation: String,
        var background: Bitmap?,
        var lastSeenMs: Long,
        var inFlight: Boolean
    )

    private val detectorBusy =
        AtomicBoolean(false)
    private val llmBusy =
        AtomicBoolean(false)

    private val api =
        VisionApiClient()

    private val tracked =
        LinkedHashMap<Int, Tracked>()

    private var nextId = 1
    private var lastAnalyzeMs = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now =
            SystemClock.elapsedRealtime()

        if (
            now - lastAnalyzeMs < 250L ||
            !detectorBusy.compareAndSet(
                false,
                true
            )
        ) {
            image.close()
            return
        }

        lastAnalyzeMs = now

        val mediaImage =
            image.image

        if (mediaImage == null) {
            detectorBusy.set(false)
            image.close()
            return
        }

        val input =
            InputImage.fromMediaImage(
                mediaImage,
                image.imageInfo.rotationDegrees
            )

        recognizer.process(input)
            .addOnSuccessListener { result ->
                try {
                    val frame =
                        ImageUtils
                            .imageProxyToUprightBitmap(
                                image
                            )

                    processFrame(
                        frame,
                        result
                    )
                    frame.recycle()
                } catch (t: Throwable) {
                    onStatus(
                        "Frame error: " +
                            (
                                t.message
                                    ?: t.javaClass.simpleName
                            )
                    )
                }
            }
            .addOnFailureListener { error ->
                onStatus(
                    "Detector error: " +
                        (
                            error.message
                                ?: error.javaClass.simpleName
                        )
                )
            }
            .addOnCompleteListener {
                image.close()
                detectorBusy.set(false)
            }
    }

    private fun processFrame(
        frame: Bitmap,
        result: Text
    ) {
        val now =
            SystemClock.elapsedRealtime()

        val detections =
            result.textBlocks
                .mapNotNull {
                    it.boundingBox
                }
                .map {
                    ImageUtils.expanded(
                        it,
                        frame.width,
                        frame.height
                    )
                }
                .filter {
                    it.width() >= 28 &&
                        it.height() >= 16
                }
                .filter {
                    it.width() * it.height() <
                        frame.width *
                        frame.height *
                        0.65f
                }
                .sortedByDescending {
                    it.width() *
                        it.height()
                }
                .take(8)

        val payloads =
            ArrayList<RoiRequest>()

        synchronized(tracked) {
            val unusedIds =
                tracked.keys.toMutableSet()

            detections.forEach { rect ->
                val match =
                    unusedIds
                        .mapNotNull { id ->
                            tracked[id]?.let {
                                id to
                                    iou(
                                        rect,
                                        it.rect
                                    )
                            }
                        }
                        .maxByOrNull {
                            it.second
                        }
                        ?.takeIf {
                            it.second >= 0.20f
                        }
                        ?.first

                val crop =
                    ImageUtils.crop(
                        frame,
                        rect
                    )

                val hash =
                    ImageUtils.averageHash(
                        crop
                    )

                val state =
                    if (match != null) {
                        unusedIds.remove(match)
                        tracked.getValue(match)
                    } else {
                        Tracked(
                            id = nextId++,
                            rect = rect,
                            hash = hash,
                            candidateHash = hash,
                            stableFrames = 0,
                            sentHash = null,
                            translation = "",
                            background = null,
                            lastSeenMs = now,
                            inFlight = false
                        ).also {
                            tracked[it.id] = it
                        }
                    }

                state.rect = rect
                state.hash = hash
                state.lastSeenMs = now

                if (
                    ImageUtils.hamming(
                        hash,
                        state.candidateHash
                    ) <= 6
                ) {
                    state.stableFrames++
                } else {
                    state.candidateHash = hash
                    state.stableFrames = 0
                }

                val changedSinceSend =
                    state.sentHash?.let {
                        ImageUtils.hamming(
                            hash,
                            it
                        ) >= 12
                    } ?: true

                if (
                    state.stableFrames >= 2 &&
                    changedSinceSend &&
                    !state.inFlight &&
                    !llmBusy.get()
                ) {
                    state.background =
                        ImageUtils.softBackground(
                            crop
                        )
                    state.inFlight = true

                    payloads +=
                        RoiRequest(
                            id = state.id,
                            bitmap = crop,
                            hash = hash
                        )
                } else {
                    crop.recycle()
                }
            }

            tracked.entries.removeAll {
                (_, state) ->
                !state.inFlight &&
                    now - state.lastSeenMs >
                    3000L
            }
        }

        publishOverlay(
            frame.width,
            frame.height
        )

        if (
            payloads.isNotEmpty() &&
            llmBusy.compareAndSet(
                false,
                true
            )
        ) {
            val selected =
                payloads.take(3)

            val dropped =
                payloads.drop(3)

            synchronized(tracked) {
                dropped.forEach {
                    tracked[it.id]
                        ?.inFlight = false
                }
            }

            dropped.forEach {
                it.bitmap.recycle()
            }

            val ids =
                selected.joinToString(",") {
                    "#\${it.id}"
                }

            onStatus(
                "Vision LLM: \$ids -> " +
                    configProvider().model
            )

            onLlmReset(
                "SEND \$ids | " +
                    "reasoning=off | max=96"
            )

            selected
                .firstOrNull()
                ?.bitmap
                ?.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )
                ?.let(onRoiPreview)

            scope.launch {
                try {
                    val result =
                        api.translate(
                            config =
                                configProvider(),
                            regions =
                                selected,
                            onDebug = {
                                onStatus(it)
                            },
                            onMessageDelta =
                                onLlmDelta
                        )

                    synchronized(tracked) {
                        selected.forEach {
                            request ->

                            tracked[
                                request.id
                            ]?.let { state ->
                                state.translation =
                                    result
                                        .translations[
                                            request.id
                                        ]
                                        .orEmpty()

                                state.sentHash =
                                    request.hash

                                state.inFlight =
                                    false
                            }
                        }
                    }

                    onStatus(
                        "Translated " +
                            "\${result.translations.size} " +
                            "region(s); " +
                            "reasoning tokens=" +
                            "\${result.reasoningTokens}"
                    )

                    publishOverlay(
                        frame.width,
                        frame.height
                    )
                } catch (t: Throwable) {
                    synchronized(tracked) {
                        selected.forEach {
                            tracked[it.id]
                                ?.inFlight = false
                        }
                    }

                    onStatus(
                        "Vision LLM error: " +
                            (
                                t.message
                                    ?: t.javaClass.simpleName
                            )
                    )

                    onLlmDelta(
                        "\n[ERROR] " +
                            (
                                t.message
                                    ?: t.javaClass.simpleName
                            )
                    )
                } finally {
                    selected.forEach {
                        it.bitmap.recycle()
                    }
                    llmBusy.set(false)
                }
            }
        } else if (
            payloads.isNotEmpty()
        ) {
            synchronized(tracked) {
                payloads.forEach {
                    tracked[it.id]
                        ?.inFlight = false
                }
            }

            payloads.forEach {
                it.bitmap.recycle()
            }
        }
    }

    private fun publishOverlay(
        frameWidth: Int,
        frameHeight: Int
    ) {
        val now =
            SystemClock.elapsedRealtime()

        val snapshot =
            synchronized(tracked) {
                tracked.values
                    .filter {
                        now - it.lastSeenMs <=
                            1500L ||
                            it.inFlight
                    }
                    .map {
                        OverlayRegion(
                            id = it.id,
                            sourceRect =
                                RectF(it.rect),
                            translation =
                                it.translation,
                            background =
                                it.background,
                            inFlight =
                                it.inFlight
                        )
                    }
            }

        onOverlay(
            frameWidth,
            frameHeight,
            snapshot
        )
    }

    private fun iou(
        a: Rect,
        b: Rect
    ): Float {
        val left =
            max(a.left, b.left)
        val top =
            max(a.top, b.top)
        val right =
            min(a.right, b.right)
        val bottom =
            min(a.bottom, b.bottom)

        val intersection =
            max(0, right - left) *
                max(0, bottom - top)

        if (intersection <= 0) {
            return 0f
        }

        val union =
            a.width() * a.height() +
                b.width() * b.height() -
                intersection

        return if (union <= 0) {
            0f
        } else {
            intersection.toFloat() /
                union.toFloat()
        }
    }
}
