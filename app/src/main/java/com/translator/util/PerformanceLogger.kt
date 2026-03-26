package com.translator.util

import android.util.Log

class PerformanceLogger {
    private val chunkMetrics = mutableMapOf<String, ChunkMetric>()

    // Data class to hold the timestamps for a single chunk
    private data class ChunkMetric(
        val text: String,
        var asrCompleteTime: Long = 0,
        var llmStartTime: Long = 0,
        var llmFirstTokenTime: Long = 0,
        var llmEndTime: Long = 0,
        var ttsStartTime: Long = 0,
        var tokenCount: Int = 0
    )

    fun startChunk(id: String, text: String) {
        chunkMetrics[id] = ChunkMetric(text = text, asrCompleteTime = System.currentTimeMillis())
    }

    fun markLlmStart(id: String) {
        chunkMetrics[id]?.llmStartTime = System.currentTimeMillis()
    }

    fun markLlmFirstToken(id: String) {
        val metric = chunkMetrics[id] ?: return
        if (metric.llmFirstTokenTime == 0L) {
            metric.llmFirstTokenTime = System.currentTimeMillis() // Only log the FIRST token
        }
        metric.tokenCount++ // Count this token
    }

    fun markLlmToken(id: String) {
        chunkMetrics[id]?.tokenCount = (chunkMetrics[id]?.tokenCount ?: 0) + 1
    }

    fun markLlmEnd(id: String) {
        chunkMetrics[id]?.llmEndTime = System.currentTimeMillis()
    }

    fun markTtsStart(id: String) {
        val metric = chunkMetrics[id] ?: return
        metric.ttsStartTime = System.currentTimeMillis()

        printReport(id)

        // Remove from map to prevent memory leaks!
        chunkMetrics.remove(id)
    }

    private fun printReport(id: String) {
        val m = chunkMetrics[id] ?: return

        val prefillTime = m.llmFirstTokenTime - m.llmStartTime
        val decodeTime = m.llmEndTime - m.llmFirstTokenTime
        val totalLlmTime = m.llmEndTime - m.llmStartTime
        val tps = if (decodeTime > 0) (m.tokenCount.toDouble() / (decodeTime / 1000.0)) else 0.0
        val tta = m.ttsStartTime - m.asrCompleteTime // Total Pipeline Latency

        Log.i("PerformanceData", """
            |
            |📊 --- Performance Report for Chunk ---
            |🗣️ Text: "${m.text}"
            |⏳ TTFT (Prefill Time): ${prefillTime}ms
            |⚡ Decode Time: ${decodeTime}ms for ${m.tokenCount} tokens
            |🚀 Throughput: ${String.format("%.2f", tps)} tokens/sec
            |🧠 Total LLM Time: ${totalLlmTime}ms
            |🏁 TTA (Total Latency ASR->TTS): ${tta}ms
            |--------------------------------------
        """.trimMargin())
    }
}