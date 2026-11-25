package com.zlatan.gamelens.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class ScreenCaptureRowCopyTest {

    @Test
    fun testDeviceFailingGeometryRowCopy() {
        // EXACT parameters from live device crash:
        // width=2772, height=1280, rowStride=11264, roiLeft=277, roiTop=838, roiWidth=2217, roiHeight=378
        val width = 2772
        val height = 1280
        val rowStride = 11264
        val roiLeft = 277
        val roiTop = 838
        val roiWidth = 2217
        val roiHeight = 378

        val totalBytes = rowStride * height
        val srcBuffer = ByteBuffer.allocate(totalBytes)

        // Write deterministic signature bytes into test rows:
        // Row 0 (y = 838):
        val row0Offset = 838 * rowStride + roiLeft * 4
        srcBuffer.put(row0Offset, 0x11.toByte())
        srcBuffer.put(row0Offset + roiWidth * 4 - 1, 0x12.toByte())

        // Row 1 (y = 839):
        val row1Offset = 839 * rowStride + roiLeft * 4
        srcBuffer.put(row1Offset, 0x21.toByte())
        srcBuffer.put(row1Offset + roiWidth * 4 - 1, 0x22.toByte())

        // Last row (y = 1215 = 838 + 378 - 1):
        val lastRowOffset = 1215 * rowStride + roiLeft * 4
        srcBuffer.put(lastRowOffset, 0x31.toByte())
        srcBuffer.put(lastRowOffset + roiWidth * 4 - 1, 0x32.toByte())

        // Destination buffer (direct / heap)
        val dstBytes = roiWidth * 4 * roiHeight
        val dstBuffer = ByteBuffer.allocate(dstBytes)
        val rowScratch = ByteArray(roiWidth * 4)

        // Execute row copy - MUST succeed without throwing any IllegalArgumentException: newPosition > limit
        val success = ScreenCaptureManager.copyRoiRgbaRows(
            src = srcBuffer,
            rowStride = rowStride,
            roiLeft = roiLeft,
            roiTop = roiTop,
            roiWidth = roiWidth,
            roiHeight = roiHeight,
            dst = dstBuffer,
            rowScratch = rowScratch
        )

        assertTrue("copyRoiRgbaRows must succeed for live device parameters", success)
        assertEquals("srcBuffer position must be reset to 0", 0, srcBuffer.position())

        // Verify copied bytes in dstBuffer:
        val roiRowBytes = roiWidth * 4
        // Row 0 verification (y = 838):
        assertEquals(0x11.toByte(), dstBuffer.get(0))
        assertEquals(0x12.toByte(), dstBuffer.get(roiRowBytes - 1))

        // Row 1 verification (y = 839):
        val dstRow1Offset = roiRowBytes
        assertEquals(0x21.toByte(), dstBuffer.get(dstRow1Offset))
        assertEquals(0x22.toByte(), dstBuffer.get(dstRow1Offset + roiRowBytes - 1))

        // Last row verification (y = 1215):
        val dstLastRowOffset = (roiHeight - 1) * roiRowBytes
        assertEquals(0x31.toByte(), dstBuffer.get(dstLastRowOffset))
        assertEquals(0x32.toByte(), dstBuffer.get(dstLastRowOffset + roiRowBytes - 1))
    }

    @Test
    fun testByteArrayOverload() {
        val rowStride = 1000
        val src = ByteBuffer.allocate(rowStride * 10)
        src.put(2 * rowStride + 10 * 4, 0xAA.toByte())

        val dst = ByteArray(50 * 4 * 5)
        val success = ScreenCaptureManager.copyRoiRgbaRows(
            src = src,
            rowStride = rowStride,
            roiLeft = 10,
            roiTop = 2,
            roiWidth = 50,
            roiHeight = 5,
            dst = dst
        )
        assertTrue(success)
        assertEquals(0xAA.toByte(), dst[0])
        assertEquals(0, src.position())
    }

    @Test
    fun testOutOfBoundsProtection() {
        val src = ByteBuffer.allocate(1000)
        val dst = ByteBuffer.allocate(1000)
        val success = ScreenCaptureManager.copyRoiRgbaRows(
            src = src,
            rowStride = 100,
            roiLeft = 0,
            roiTop = 9,
            roiWidth = 50, // 50 * 4 = 200 bytes, row 9 requires 900 + 200 = 1100 > 1000
            roiHeight = 1,
            dst = dst
        )
        assertFalse("Must return false if ROI exceeds buffer limit", success)
    }
}
