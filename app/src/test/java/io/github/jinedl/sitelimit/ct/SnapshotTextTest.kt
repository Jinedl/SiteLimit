package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTextTest {
    private val nodes = listOf(
        UiNode(cls = "FrameLayout", desc = "Новости Москвы\n12 345 подписчиков", top = 60, bottom = 160, left = 0, right = 1080, clickable = true),
        UiNode(id = "reel_recycler", cls = "RecyclerView", top = 200, bottom = 2000, selected = true),
        UiNode(cls = "EditText", hint = "Сообщение", text = "привет \"мир\"", top = 2000, bottom = 2100, editable = true),
    )
    private val screen = Screen("org.telegram.messenger", 1080, 2107, nodes)
    private val keywords = listOf("подписчик", "сообщение")

    @Test
    fun maskKeepsKeywordsAndStructure() {
        assertEquals("••••••• ••••••\n00 000 подписчиков", SnapshotText.mask("Новости Москвы\n12 345 подписчиков", keywords))
        assertEquals("Сообщение", SnapshotText.mask("Сообщение", keywords))
    }

    @Test
    fun maskedSnapshotHasNoPrivateLetters() {
        val text = SnapshotText.format(screen, listOf("тест"), keywords, mask = true)
        assertFalse(text.contains("Новости"))
        assertFalse(text.contains("привет"))
        assertTrue(text.contains("подписчиков"))
        assertTrue(text.contains("id=reel_recycler"))
    }

    @Test
    fun plainSnapshotIsCut() {
        val text = SnapshotText.format(screen, emptyList(), keywords, mask = false)
        assertTrue(text, text.contains("desc=\"Новости Москвы\\n12 34…\"(33)"))
        assertTrue(text, text.contains("⚑подписчик"))
    }

    @Test
    fun roundTrip() {
        val text = SnapshotText.format(screen, listOf("заголовок"), keywords, mask = true)
        val back = SnapshotText.parse(text)
        assertEquals("org.telegram.messenger", back.pkg)
        assertEquals(2107, back.height)
        assertEquals(3, back.nodes.size)
        val head = back.nodes[0]
        assertEquals("FrameLayout", head.cls)
        assertEquals("••••••• ••••••\n00 000 подписчиков", head.desc)
        assertEquals(60, head.top)
        assertTrue(head.clickable)
        val reel = back.nodes[1]
        assertEquals("reel_recycler", reel.id)
        assertTrue(reel.selected)
        val input = back.nodes[2]
        assertTrue(input.editable)
        assertEquals("Сообщение", input.hint)
        assertEquals("•••••• \"•••\"", input.text)
    }
}
