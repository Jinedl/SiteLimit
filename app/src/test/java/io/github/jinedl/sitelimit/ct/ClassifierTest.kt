package io.github.jinedl.sitelimit.ct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Распознавание по образцам снимков из src/test/resources/snapshots.
 * Каждый образец начинается с «# expect: сервис тип [название]».
 */
class ClassifierTest {
    private val book: RuleBook by lazy { RuleBook.parse(File("src/main/assets/content_rules.json").readText()) }

    private fun serviceFor(pkg: String): ServiceDef? = book.services.firstOrNull { s -> s.packages.any { it.name == pkg } }

    @Test
    fun rulesFileIsValid() {
        assertEquals(listOf("telegram", "max", "vk", "youtube"), book.services.map { it.id })
        val ids = HashSet<String>()
        for (s in book.services) {
            assertTrue("${s.id}: нет пакетов", s.packages.isNotEmpty())
            for (p in s.packages) assertTrue("${p.name}: статус ${p.status}", p.status in setOf("snap", "store", "guess"))
            for (r in s.screens) {
                assertTrue("правило ${r.id} повторяется", ids.add(r.id))
                assertTrue("${r.id}: статус ${r.status}", r.status in setOf("snap", "src", "guess"))
                assertTrue("${r.id}: неизвестно не задаётся правилом", r.type != ContentType.UNKNOWN)
            }
        }
        assertTrue(book.allKeywords.contains("подписчик"))
    }

    @Test
    fun allSnapshotSamples() {
        val files = File("src/test/resources/snapshots").listFiles { f -> f.name.endsWith(".txt") }!!.sortedBy { it.name }
        assertTrue("нет образцов", files.size >= 10)
        val errors = ArrayList<String>()
        for (f in files) {
            val text = f.readText()
            val expect = text.lineSequence().firstOrNull { it.startsWith("# expect:") }
                ?.removePrefix("# expect:")?.trim()?.split(' ', limit = 3)
                ?: throw AssertionError("${f.name}: нет строки # expect")
            val screen = SnapshotText.parse(text)
            val svc = serviceFor(screen.pkg) ?: run {
                errors.add("${f.name}: пакет ${screen.pkg} не найден в правилах")
                null
            } ?: continue
            val det = Classifier.classify(svc, screen)
            val want = "${expect[0]} ${expect[1]}" + (expect.getOrNull(2)?.let { " «$it»" } ?: "")
            val got = "${det.service} ${det.type.id}" + (if (expect.size > 2) " «${det.title}»" else "")
            if (want != got) errors.add("${f.name}: ждали $want, получили $got (правило ${det.rule})")
        }
        if (errors.isNotEmpty()) fail(errors.joinToString("\n"))
    }

    @Test
    fun firstMatchingRuleWins() {
        val rb = RuleBook.parse(
            """{"services": [{"id": "s", "packages": ["p"], "screens": [
                {"id": "a", "type": "shorts", "all": [{"idHas": "reel"}]},
                {"id": "b", "type": "feed", "all": [{"selected": true}]}
            ]}]}""",
        )
        val svc = rb.service("s")!!
        val both = Screen("p", 1080, 2000, listOf(UiNode(id = "reel_x"), UiNode(selected = true)))
        assertEquals("a", Classifier.classify(svc, both).rule)
        val feed = Screen("p", 1080, 2000, listOf(UiNode(selected = true)))
        assertEquals(ContentType.FEED, Classifier.classify(svc, feed).type)
        val none = Screen("p", 1080, 2000, listOf(UiNode(text = "x")))
        val d = Classifier.classify(svc, none)
        assertEquals(ContentType.UNKNOWN, d.type)
        assertNull(d.rule)
    }

    @Test
    fun conditionsAllAnyNoneAndRegion() {
        val rb = RuleBook.parse(
            """{"services": [{"id": "s", "packages": ["p"], "default": "unknown", "screens": [
                {"id": "r", "type": "channel",
                 "all": [{"textHas": "ПОДПИСЧИК", "yMax": 0.2}],
                 "any": [{"desc": "канал"}, {"cls": "imageview"}],
                 "none": [{"editable": true}]},
                {"id": "two", "type": "feed", "all": [{"idHas": "card", "min": 2}]}
            ]}]}""",
        )
        val svc = rb.service("s")!!
        fun s(vararg n: UiNode) = Screen("p", 1000, 1000, n.toList())
        val top = UiNode(text = "5 подписчиков", top = 100)
        assertEquals(ContentType.CHANNEL, Classifier.classify(svc, s(top, UiNode(desc = " Канал "))).type)
        assertEquals(ContentType.CHANNEL, Classifier.classify(svc, s(top, UiNode(cls = "ImageView"))).type)
        assertEquals(ContentType.UNKNOWN, Classifier.classify(svc, s(top)).type)                                  // нет any
        assertEquals(ContentType.UNKNOWN, Classifier.classify(svc, s(top, UiNode(desc = "канал"), UiNode(editable = true))).type) // none
        assertEquals(ContentType.UNKNOWN, Classifier.classify(svc, s(UiNode(text = "5 подписчиков", top = 300), UiNode(desc = "канал"))).type) // ниже 20%
        assertEquals(ContentType.UNKNOWN, Classifier.classify(svc, s(UiNode(id = "card1"))).type)
        assertEquals(ContentType.FEED, Classifier.classify(svc, s(UiNode(id = "card1"), UiNode(id = "card2"))).type)
    }

    @Test
    fun packageFilterAndTitleExtraction() {
        val rb = RuleBook.parse(
            """{"services": [{"id": "s", "packages": ["p1", "p2"], "screens": [
                {"id": "only2", "type": "chat", "packages": ["p2"]},
                {"id": "t", "type": "channel", "all": [{"textHas": "подписчик"}],
                 "title": [{"from": {"textHas": "нет такого"}}, {"from": {"textHas": "подписчик"}, "pick": "above"}]},
                {"id": "re", "type": "feed", "all": [{"descHas": "лента"}],
                 "title": {"from": {"descHas": "лента"}, "field": "desc", "re": "^Лента: (.+)$"}}
            ]}]}""",
        )
        val svc = rb.service("s")!!
        assertEquals(ContentType.CHAT, Classifier.classify(svc, Screen("p2", 0, 0, emptyList())).type)
        val ch = Classifier.classify(svc, Screen("p1", 1000, 1000, listOf(
            UiNode(text = "Шапка", top = 10, bottom = 40),
            UiNode(text = "Мой канал", top = 50, bottom = 90),
            UiNode(text = "10 подписчиков", top = 95, bottom = 120),
        )))
        assertEquals("Мой канал", ch.title)
        val feed = Classifier.classify(svc, Screen("p1", 1000, 1000, listOf(UiNode(desc = "Лента: Друзья"))))
        assertEquals("Друзья", feed.title)
    }

    @Test
    fun badRulesAreReportedClearly() {
        fun err(json: String): String = try {
            RuleBook.parse(json)
            fail("ждали ошибку")
            ""
        } catch (e: MiniJson.Error) {
            e.message!!
        }
        assertTrue(err("""{"services": [{"id": "s", "screens": [{"id": "x", "type": "лента"}]}]}""").contains("неизвестный type"))
        assertTrue(err("""{"services": [{"id": "s", "screens": [{"id": "x", "type": "feed", "all": [{"txtHas": "a"}]}]}]}""").contains("txtHas"))
        assertTrue(err("""{"services": [{"id": "s", "screens": [{"id": "x", "type": "feed", "all": [{"textRe": "(("}]}]}]}""").contains("textRe"))
        assertTrue(err("""{"services": []}""").contains("нет ни одного"))
        assertTrue(err("""{"services": [{"id": "s", "screens": [{"id": "x", "type": "feed", "all": [{}]}]}]}""").contains("пустое"))
    }
}
