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
        // ALAC uncompressed frame header (参考 shairport-sync / apple-lossless 规范)
        // 帧头由若干 bit 字段组成，以 big-endian 位流打包：
        //   [0]     = 0  (hasTimestamp = false)
        //   [1]     = 0  (不用)
        //   [2]     = 1  (uncompressed flag = 1)
        //   [3]     = 0  (hasSize = 0, 使用 SDP 中声明的标准帧大小 352)
        //   [4..7]  = 0000 (reserved)
        //   共 1 字节 = 0b00100000 = 0x20
        //
        // 非标准帧大小时需要追加 hasSize=1 + 32-bit 样本数：
        //   byte0 = 0b00101000 = 0x28 (hasSize=1)
        //   bytes 1..4 = numSamples (big-endian uint32)
        
        return if (numSamples == FRAMES_PER_PACKET) {
            // 标准帧大小（352 frames），1 字节帧头，其余全是 PCM 数据
            byteArrayOf(0x20.toByte())
        } else {
            // 非标准帧大小，5 字节帧头
            val buffer = ByteBuffer.allocate(5)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.put(0x28.toByte())     // hasSize=1
            buffer.putInt(numSamples)     // 样本帧数
            buffer.array()
        }
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
