package app.chatlens

import app.chatlens.agent.SearchResultPicker
import app.chatlens.core.Bounds
import app.chatlens.core.UiNode
import app.chatlens.profile.SectionKind
import app.chatlens.profile.SelectorProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests of hit selection on synthetic search-result trees (screen 1080 x 2400).
 * They check the selection logic, not the real WhatsApp layout.
 */
class SearchResultPickerTest {

    private val json = File("src/main/assets/profiles/whatsapp.json").readText()
    private val profile = SelectorProfile.parse(json)

    private fun tv(text: String, l: Int, t: Int, r: Int, b: Int, visible: Boolean = true) =
        UiNode("android.widget.TextView", null, text, null, Bounds(l, t, r, b), visible = visible)

    private fun header(text: String, top: Int) = tv(text, 40, top, 600, top + 60)

    /** Clickable row with a name and a preview, 160 px tall. */
    private fun chatRow(name: String, preview: String, top: Int) = UiNode(
        "android.view.ViewGroup", "com.whatsapp:id/row", null, null, Bounds(0, top, 1080, top + 160), clickable = true,
        children = listOf(tv(name, 160, top + 20, 800, top + 70), tv(preview, 160, top + 85, 900, top + 140)),
    )

    private fun screen(vararg items: UiNode, withField: Boolean = true, listClickable: Boolean = false): UiNode {
        val field = UiNode("android.widget.EditText", null, "Terry Benedikt", null, Bounds(100, 80, 900, 200), editable = true)
        val list = UiNode(
            "androidx.recyclerview.widget.RecyclerView", null, null, null, Bounds(0, 260, 1080, 1500),
            scrollable = true, clickable = listClickable, children = items.toList(),
        )
        return UiNode(
            "android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1080, 2400),
            children = listOfNotNull(if (withField) field else null, list),
        )
    }

    @Test
    fun picksChatRowInChatsSectionAndExposesClickableParent() {
        val row = chatRow("Terry Benedikt", "Bis morgen", 340)
        val root = screen(header("Chats", 270), row, header("Gemeinsame Gruppen", 520), chatRow("Familie", "Terry Benedikt, Anna", 590))
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        val hit = res.hit
        assertNotNull(res.summary(), hit)
        assertEquals(SectionKind.CHATS, hit!!.section)
        assertSame(row.children[0], hit.title)
        assertSame(row, hit.clickTarget)
        assertEquals(false, hit.viaFallback)
    }

    @Test
    fun caseInsensitiveAndTrimmed() {
        val root = screen(header("Chats", 270), chatRow("  terry BENEDIKT ", "Hallo", 340))
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNotNull(res.hit)
    }

    @Test
    fun partialAndPreviewMatchesAreIgnored() {
        val root = screen(
            header("Chats", 270),
            chatRow("Terry Benedikt Junior", "Terry Benedikt: Hallo", 340),
            chatRow("Anna", "Terry Benedikt war da", 520),
        )
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNull(res.hit)
        assertEquals(0, res.candidates.size)
    }

    @Test
    fun groupSectionIsNeverChosen() {
        val root = screen(
            header("Gemeinsame Gruppen", 270),
            chatRow("Terry Benedikt", "Gruppe", 340),
        )
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNull(res.summary(), res.hit)
        assertEquals(SectionKind.DENIED, res.candidates.single().section)
    }

    @Test
    fun chatsHitBelowGroupsHitIsChosen() {
        val groupRow = chatRow("Terry Benedikt", "Gruppe", 340)
        val chatsRow = chatRow("Terry Benedikt", "Hallo", 700)
        val root = screen(header("Gemeinsame Gruppen", 270), groupRow, header("Chats", 560), chatsRow)
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertSame(chatsRow, res.hit!!.clickTarget)
        assertEquals(2, res.candidates.size)
    }

    @Test
    fun englishAndOtherSectionsAreRejected() {
        val contacts = screen(header("Kontakte", 270), chatRow("Terry Benedikt", "Hey", 340))
        assertNull(SearchResultPicker.pick(contacts, "Terry Benedikt", profile).hit)
        val groupsEn = screen(header("Groups in common", 270), chatRow("Terry Benedikt", "x", 340))
        assertNull(SearchResultPicker.pick(groupsEn, "Terry Benedikt", profile).hit)
        val chatsEn = screen(header("CHATS", 270), chatRow("Terry Benedikt", "x", 340))
        assertNotNull(SearchResultPicker.pick(chatsEn, "Terry Benedikt", profile).hit)
    }

    @Test
    fun hitWithoutHeaderAboveIsRejectedWhenOtherHeadersAreVisible() {
        // The "Chats" header has scrolled away; only the section below it is still visible
        val root = screen(chatRow("Terry Benedikt", "Hallo", 300), header("Gemeinsame Gruppen", 520), chatRow("Familie", "x", 590))
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNull(res.summary(), res.hit)
        assertEquals(1, res.headersSeen)
        assertNull(res.candidates.single().section)
    }

    @Test
    fun fallbackOnlyWhenNoKnownHeaderAtAll() {
        val root = screen(chatRow("Anna", "x", 300), chatRow("Terry Benedikt", "Hallo", 480))
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNotNull(res.hit)
        assertTrue(res.hit!!.viaFallback)
        val off = SelectorProfile.parse(json.replace("\"searchFallbackWithoutHeaders\": true", "\"searchFallbackWithoutHeaders\": false"))
        assertNull(SearchResultPicker.pick(root, "Terry Benedikt", off).hit)
    }

    @Test
    fun searchFieldAndInvisibleNodesAreIgnored() {
        // The search field contains the title (editable, at the top) and must never count as a hit
        val root = screen(header("Chats", 270))
        assertNull(SearchResultPicker.pick(root, "Terry Benedikt", profile).hit)
        val hidden = screen(header("Chats", 270), UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 340, 1080, 500), clickable = true,
            children = listOf(tv("Terry Benedikt", 160, 360, 800, 410, visible = false)),
        ))
        assertNull(SearchResultPicker.pick(hidden, "Terry Benedikt", profile).hit)
    }

    @Test
    fun hugeOrHeaderContainingClickableAncestorIsNotUsedAsClickTarget() {
        // The whole list is marked clickable and contains headers: not a click target (the caller then uses the gesture)
        val root = screen(header("Chats", 270), UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 340, 1080, 500),
            children = listOf(tv("Terry Benedikt", 160, 360, 800, 410)),
        ), listClickable = true)
        val hit = SearchResultPicker.pick(root, "Terry Benedikt", profile).hit
        assertNotNull(hit)
        assertNull(hit!!.clickTarget)
    }

    @Test
    fun titleBlankGivesNoHit() {
        val root = screen(header("Chats", 270), chatRow("Terry Benedikt", "x", 340))
        assertNull(SearchResultPicker.pick(root, "  ", profile).hit)
    }

    @Test
    fun oldProfilesWithoutSectionKeysUseDefaults() {
        val stripped = json.lines().filterNot { it.contains("searchSections") || it.contains("searchFallbackWithoutHeaders") || it.contains("readMoreTexts") || it.contains("chatStartPatterns") }
            .joinToString("\n").replace("\"denyClickIdSubstrings\": [\"send\", \"entry\", \"voice\", \"camera\", \"attach\", \"emoji\", \"mic\"],", "\"denyClickIdSubstrings\": [\"send\"]")
        val p = SelectorProfile.parse(stripped)
        assertEquals(SectionKind.CHATS, p.sectionKindOf("Chats"))
        assertEquals(SectionKind.DENIED, p.sectionKindOf("Gemeinsame Gruppen"))
    }

    @Test
    fun hitHighUpOnTallDisplayIsFoundBelowSearchField() {
        // 1440 x 3200 pixels: 25 percent of the height is 800 px. The hit is at y=480 and must still be found.
        val field = UiNode("android.widget.EditText", null, "Terry Benedikt", null, Bounds(100, 100, 1300, 250), editable = true)
        val row = UiNode(
            "android.view.ViewGroup", null, null, null, Bounds(0, 400, 1440, 560), clickable = true,
            children = listOf(tv("Terry Benedikt", 200, 420, 900, 480), tv("Bis morgen", 200, 490, 1200, 540)),
        )
        val list = UiNode(
            "androidx.recyclerview.widget.RecyclerView", null, null, null, Bounds(0, 260, 1440, 1900), scrollable = true,
            children = listOf(tv("Chats", 40, 300, 400, 380), row),
        )
        val root = UiNode("android.widget.FrameLayout", null, null, null, Bounds(0, 0, 1440, 3200), children = listOf(field, list))
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNotNull(res.summary(), res.hit)
        assertSame(row, res.hit!!.clickTarget)
        assertEquals(250, res.searchBottom)
    }

    @Test
    fun editableFieldWithTitleNeverCountsAsHeaderMatchSource() {
        // With no hit in the list, the search field (text equals the title) must not count as a hit
        val root = screen(header("Chats", 270))
        val res = SearchResultPicker.pick(root, "Terry Benedikt", profile)
        assertNull(res.hit)
        assertEquals(0, res.candidates.size)
    }
}
