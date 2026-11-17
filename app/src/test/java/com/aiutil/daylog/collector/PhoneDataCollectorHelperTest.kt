package com.aiutil.daylog.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDataCollectorHelperTest {

    @Test
    fun `스크린샷 폴더와 파일명을 정확히 판별한다`() {
        assertTrue(PhoneDataCollector.isScreenshotFile("Screenshots", "Screenshot_20261117.png"))
        assertTrue(PhoneDataCollector.isScreenshotFile("Pictures", "스크린샷_1.jpg"))
        assertFalse(PhoneDataCollector.isScreenshotFile("Camera", "IMG_20261117_120000.jpg"))
    }
}
