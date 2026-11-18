package io.github.jinedl.sitelimit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Лимиты сайтов v0.9 не должны поменяться: разбор адреса и поддомены. */
class SitesV09Test {

    @Test
    fun normalizeDomain() {
        assertEquals("youtube.com", Store.normalizeDomain("https://www.YouTube.com/watch?v=1"))
        assertEquals("support.google.com", Store.normalizeDomain("support.google.com"))
        assertEquals("m.vk.com", Store.normalizeDomain("m.vk.com/feed"))
        assertEquals("пример.рф", Store.normalizeDomain("пример.рф"))
        assertNull(Store.normalizeDomain("Поиск в Яндексе"))
        assertNull(Store.normalizeDomain(""))
        assertNull(Store.normalizeDomain(null))
    }

    @Test
    fun matchLimitWithSubdomains() {
        val limits = listOf("youtube.com", "vk.com", "music.youtube.com")
        assertEquals("youtube.com", Store.matchLimit("m.youtube.com", limits))
        assertEquals("music.youtube.com", Store.matchLimit("music.youtube.com", limits))
        assertEquals("vk.com", Store.matchLimit("vk.com", limits))
        assertNull(Store.matchLimit("notvk.com", limits))
    }
}
