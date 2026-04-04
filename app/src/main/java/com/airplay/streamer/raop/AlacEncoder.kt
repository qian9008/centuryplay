package com.airplay.streamer.raop

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple ALAC (Apple Lossless Audio Codec) encoder for AirPlay 1
 * 
 * For AirPlay 1, we can use uncompressed ALAC frames which are accepted
 * by most receivers. This is a simplified implementation that wraps PCM
 * data in ALAC frame format.
 */
class AlacEncoder {
    companion object {
        private const val FRAMES_PER_PACKET = 352
        private const val CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE
    }

    /**
     * Encode PCM audio data to ALAC format
     * Input: 16-bit stereo PCM, 44100Hz, little-endian
     * Output: ALAC frame suitable for RTP streaming
     */
    fun encode(pcmData: ByteArray): ByteArray {
        // For simplicity, we use uncompressed ALAC mode
        // This is less efficient but widely compatible
        
        val numSamples = pcmData.size / BYTES_PER_FRAME
        
        // ALAC packet header for uncompressed mode
        // Format: 1 byte header + PCM data (converted to big-endian)
        val header = buildAlacHeader(numSamples)
        
        // Convert PCM from little-endian to big-endian (network byte order)
        val bigEndianPcm = convertToBigEndian(pcmData)
        
        return header + bigEndianPcm
    }

    private fun buildAlacHeader(numSamples: Int): ByteArray {
        // ALAC uncompressed frame header (参考 Apple 官方规范和 shairport-sync)
        // 未压缩 ALAC 帧格式：
        //   byte 0: 0x00 (未压缩标志)
        //   byte 1-4: 样本数 (big-endian, 352 for standard packets)
        //   byte 5-8: 0x00000000 (reserved)
        //   总共 9 字节头部 + PCM 数据
        
        val buffer = ByteBuffer.allocate(9)
        buffer.order(ByteOrder.BIG_ENDIAN)
        
        // 未压缩标志 (0 = 未压缩)
        buffer.put(0x00.toByte())
        
        // 样本数 (352 for standard AirPlay packets)
        buffer.putInt(numSamples)
        
        // 保留字段
        buffer.putInt(0)
        
        return buffer.array()
    }

    private fun convertToBigEndian(pcmData: ByteArray): ByteArray {
        val result = ByteArray(pcmData.size)
        
        // Swap bytes for each 16-bit sample
        for (i in pcmData.indices step 2) {
            if (i + 1 < pcmData.size) {
                result[i] = pcmData[i + 1]
                result[i + 1] = pcmData[i]
            }
        }
        
        return result
    }

    /**
     * Get the expected PCM buffer size for one ALAC packet
     */
    fun getExpectedPcmSize(): Int {
        return FRAMES_PER_PACKET * BYTES_PER_FRAME
    }
}
