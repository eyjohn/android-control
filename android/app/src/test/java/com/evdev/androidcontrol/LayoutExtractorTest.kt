package com.evdev.androidcontrol

import com.evdev.androidcontrol.service.LayoutExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutExtractorTest {

    @Test
    fun testNullRootNodeReturnsEmptyList() {
        val result = LayoutExtractor.extract(null, 1080, 2400)
        assertEquals("unknown", result.package_name)
        assertEquals(1080, result.screen_width)
        assertEquals(2400, result.screen_height)
        assertEquals(0, result.elements.size)
    }
}

