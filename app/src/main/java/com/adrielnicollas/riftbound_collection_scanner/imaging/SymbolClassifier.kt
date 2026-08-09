package com.adrielnicollas.riftbound_collection_scanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class SymbolPrediction(
    val label: String,
    val confidence: Float,
)

class SymbolClassifier(context: Context) : Closeable {
    private val interpreter = Interpreter(loadModel(context))
    private val labels = context.assets.open(LABELS_FILE).bufferedReader().useLines { lines ->
        lines.map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun classify(bitmap: Bitmap): SymbolPrediction? {
        if (labels.isEmpty()) return null

        val inputTensor = interpreter.getInputTensor(0)
        val inputShape = inputTensor.shape()
        val imageSize = inputShape.getOrNull(1) ?: MODEL_IMAGE_SIZE
        val input = buildInput(bitmap, imageSize, inputTensor.dataType())
        val output = Array(1) { FloatArray(labels.size) }

        interpreter.run(input, output)

        val best = output[0].withIndex().maxByOrNull { it.value } ?: return null
        return SymbolPrediction(
            label = labels.getOrNull(best.index).orEmpty(),
            confidence = best.value,
        ).takeIf { it.label.isNotBlank() }
    }

    override fun close() {
        interpreter.close()
    }

    private fun buildInput(bitmap: Bitmap, imageSize: Int, dataType: DataType): ByteBuffer {
        val bytesPerChannel = if (dataType == DataType.UINT8) 1 else 4
        val buffer = ByteBuffer.allocateDirect(imageSize * imageSize * CHANNEL_COUNT * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        val resized = Bitmap.createScaledBitmap(bitmap, imageSize, imageSize, true)

        try {
            for (y in 0 until imageSize) {
                for (x in 0 until imageSize) {
                    val color = resized.getPixel(x, y)
                    if (dataType == DataType.UINT8) {
                        buffer.put(Color.red(color).toByte())
                        buffer.put(Color.green(color).toByte())
                        buffer.put(Color.blue(color).toByte())
                    } else {
                        buffer.putFloat(Color.red(color).toFloat())
                        buffer.putFloat(Color.green(color).toFloat())
                        buffer.putFloat(Color.blue(color).toFloat())
                    }
                }
            }
        } finally {
            if (resized !== bitmap) {
                resized.recycle()
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun loadModel(context: Context): ByteBuffer {
        val bytes = context.assets.open(MODEL_FILE).use { input -> input.readBytes() }
        return ByteBuffer.allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .apply {
                put(bytes)
                rewind()
            }
    }

    companion object {
        private const val MODEL_FILE = "riftbound_symbol_classifier.tflite"
        private const val LABELS_FILE = "riftbound_symbol_labels.txt"
        private const val CHANNEL_COUNT = 3
        private const val MODEL_IMAGE_SIZE = 160
    }
}
