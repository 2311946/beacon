package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

class StickerStoreBatchTest {
    private val first = "https://example.com/First.PNG?token=AbC"
    private val second = "http://example.com/second.gif"

    private fun newStore(preferences: MemoryPreferences): StickerStore {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = java.io.File(System.getProperty("java.io.tmpdir"), "beacon-sticker-test-files")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                assertEquals("url_stickers", name)
                assertEquals(Context.MODE_PRIVATE, mode)
                return preferences
            }
        }
        // 每项测试创建隔离实例，不改动生产单例或绕过真实初始化逻辑。
        return StickerStore::class.java.getDeclaredConstructor(Context::class.java).run {
            isAccessible = true
            newInstance(context)
        }
    }

    @Test
    fun `local and remote stickers restore together without losing groups`() {
        val prefs = MemoryPreferences()
        val local = java.io.File(System.getProperty("java.io.tmpdir"),
            "beacon-sticker-test-files/stickers/${UUID.randomUUID()}.png").toURI().toString()
            .replace("file:/", "file:///")
        val groups = listOf(StickerGroup(UUID.randomUUID().toString(), "混合", listOf(
            Sticker(UUID.randomUUID().toString(), local), Sticker(UUID.randomUUID().toString(), first),
        )))
        prefs.edit().putString("groups", Json.encodeToString(groups)).commit()
        val restored = newStore(prefs)
        assertNull(restored.error.value)
        assertEquals(groups, restored.groups.value)
        assertThrows(IllegalArgumentException::class.java) { restored.isLocalImageForRequest(local) }
    }

    @Test
    fun `arbitrary local paths cannot become upload attachments`() {
        val store = newStore(MemoryPreferences())
        assertEquals(false, store.isLocalImageForRequest("file:///etc/passwd"))
        assertEquals(false, store.isLocalImageForRequest("file:///tmp/stickers/not-a-uuid.png"))
        assertEquals(false, store.isLocalImageForRequest(first))
    }

    @Test
    fun `sticker size defaults to medium and survives restart without changing groups`() {
        val prefs = MemoryPreferences()
        val store = newStore(prefs)
        assertEquals(StickerDisplaySize.MEDIUM, store.displaySize.value)
        val group = store.addGroup("测试")
        store.addSticker(group.id, first)
        val groups = store.groups.value
        store.setDisplaySize(StickerDisplaySize.SMALL)
        assertEquals(StickerDisplaySize.SMALL, newStore(prefs).displaySize.value)
        assertEquals(groups, newStore(prefs).groups.value)
        store.setDisplaySize(StickerDisplaySize.LARGE)
        assertEquals(StickerDisplaySize.LARGE, newStore(prefs).displaySize.value)
    }

    @Test
    fun `size limits and unknown setting fallback are stable`() {
        assertEquals(StickerDisplaySize.MEDIUM, StickerDisplaySize.fromStored("unknown"))
        assertEquals(0.5f, StickerDisplaySize.MEDIUM.widthFraction)
        assertEquals(150, StickerDisplaySize.MEDIUM.maxHeightDp)
        assertTrue(StickerDisplaySize.SMALL.maxHeightDp < StickerDisplaySize.MEDIUM.maxHeightDp)
        assertTrue(StickerDisplaySize.LARGE.maxHeightDp > StickerDisplaySize.MEDIUM.maxHeightDp)
    }

    @Test
    fun `only labeled or collected images are treated as stickers`() {
        val groups = listOf(StickerGroup("group", "测试", listOf(Sticker("id", first))))
        assertTrue(isStickerImage(first, null, groups))
        assertTrue(isStickerImage(second, "表情", emptyList()))
        assertTrue(isStickerImage(second, "STICKER", emptyList()))
        assertEquals(false, isStickerImage(second, "普通照片", groups))
        assertEquals(false, isStickerImage(second, null, groups))
    }

    @Test
    fun `blank lines are ignored and accepted URLs keep their input order`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, "\r\n \t\r\n $first \r\n\r\n\t$second\t\r\n")

        assertEquals(listOf(first, second), added.map { it.url })
        assertEquals(added, store.groups.value.single().stickers)
        assertEquals(added.size, added.map { it.id }.toSet().size)
        added.forEach { assertEquals(it.id, UUID.fromString(it.id).toString()) }
    }

    @Test
    fun `schemes are normalized without lowercasing path query or fragment`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, " HTTPS://example.com/First.PNG?token=AbC#View \nHtTp://example.com/second.gif")

        assertEquals(listOf("$first#View", second), added.map { it.url })
    }

    @Test
    fun `named entries accept both colon styles mixed with plain URLs`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, " 开心 ： $first \r\n抱抱:$second\nhttps://example.com/third.png")

        assertEquals(listOf(first, second, "https://example.com/third.png"), added.map { it.url })
        assertEquals(listOf("开心", "抱抱", ""), added.map { it.name })
        assertEquals(added, newStore(preferences).groups.value.single().stickers)
    }

    @Test
    fun `names on previous lines accept both trailing colons and skip blank lines`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, " 开心： \r\n\r\n $first \r\n抱抱:\n \t\n$second")

        assertEquals(listOf(first, second), added.map { it.url })
        assertEquals(listOf("开心", "抱抱"), added.map { it.name })
        assertEquals(added, newStore(preferences).groups.value.single().stickers)
    }

    @Test
    fun `split names inline names and unnamed URLs can be mixed without leaking names`() {
        val store = newStore(MemoryPreferences())
        val group = store.addGroup("表情")
        val third = "https://example.com/third.png"
        val added = store.addStickers(group.id, "开心\n$first\n$second\n候选名称：\n抱抱:$third\n$first\n未配对名称：")

        assertEquals(listOf(first, second, third, first), added.map { it.url })
        assertEquals(listOf("开心", "", "抱抱", ""), added.map { it.name })
    }

    @Test
    fun `only most recent text line names the next URL and uses existing normalization`() {
        val store = newStore(MemoryPreferences())
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, "旧名称：\n [抱抱]  【委屈】 ：\n$first\n${"啊".repeat(90)}:\n$second")

        assertEquals(listOf("抱抱 委屈", "啊".repeat(80)), added.map { it.name })
    }

    @Test
    fun `invalid HTTP lines consume pending names and uppercase URLs keep their content`() {
        val store = newStore(MemoryPreferences())
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, "不能串名：\nhttps://example.com:0/bad.png\n$first\n开心:\nHTTPS://example.com/First.PNG?token=AbC#View\n$second")

        assertEquals(listOf(first, "$first#View", second), added.map { it.url })
        assertEquals(listOf("", "开心", ""), added.map { it.name })
    }

    @Test
    fun `split named batch still writes all valid stickers in one commit`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val commits = preferences.commitCalls
        val added = store.addStickers(group.id, "开心：\n$first\n抱抱：\n$second")

        assertEquals(commits + 1, preferences.commitCalls)
        assertEquals(added, store.groups.value.single().stickers)
    }

    @Test
    fun `URL colons and case remain intact and empty names are allowed`() {
        val store = newStore(MemoryPreferences())
        val group = store.addGroup("表情")
        val url = "https://example.com:8443/a:b.png?token=Ab:C#x:y"
        val added = store.addStickers(group.id, "$url\n委屈：HTTPS://example.com:8443/a:b.png?token=Ab:C#x:y\n：$first\n:$second")

        assertEquals(listOf(url, url, first, second), added.map { it.url })
        assertEquals(listOf("", "委屈", "", ""), added.map { it.name })
    }

    @Test
    fun `names use existing normalization without being validated as URLs`() {
        val store = newStore(MemoryPreferences())
        val group = store.addGroup("表情")
        val added = store.addStickers(group.id, " [抱抱]  【委屈】 ： $first\n${"啊".repeat(90)}:$second")

        assertEquals(listOf("抱抱 委屈", "啊".repeat(80)), added.map { it.name })
        assertEquals(listOf(first, second), added.map { it.url })
    }

    @Test
    fun `invalid lines are skipped while valid entries are committed once`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val beforeEdits = preferences.editCalls
        val beforeCommits = preferences.commitCalls
        val added = store.addStickers(group.id, "\r\n$first\r\nftp://example.com/a.png\r\n \t\r\nhttps://example.com:0/a.png\r\nhttps://example.com/a b.png\r\n说明文字\n坏图：not-a-url\n坏图:file:///tmp/a.png\n空地址：\n抱抱:$second")

        assertEquals(listOf(first, second), added.map { it.url })
        assertEquals(listOf("", "抱抱"), added.map { it.name })
        assertEquals(beforeEdits + 1, preferences.editCalls)
        assertEquals(beforeCommits + 1, preferences.commitCalls)
        assertEquals(added, store.groups.value.single().stickers)
    }

    @Test
    fun `credential URLs are skipped without blocking valid items in the batch`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        store.addSticker(group.id, first)
        val before = store.groups.value
        val beforeEdits = preferences.editCalls
        val beforeCommits = preferences.commitCalls

        val added = store.addStickers(group.id, "$second\nhttps://user:password@example.com/a.png\n名称：https://user:password@example.com/b.png\nhttps://example.com/third.png")

        assertEquals(listOf(second, "https://example.com/third.png"), added.map { it.url })
        assertEquals(before.single().stickers + added, store.groups.value.single().stickers)
        assertEquals(beforeEdits + 1, preferences.editCalls)
        assertEquals(beforeCommits + 1, preferences.commitCalls)
        assertEquals(store.groups.value, newStore(preferences).groups.value)
    }

    @Test
    fun `empty batches neither edit preferences nor publish a snapshot`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val before = store.groups.value
        val persisted = preferences.snapshot()
        val beforeEdits = preferences.editCalls
        val beforeCommits = preferences.commitCalls

        listOf("", "\n", " \t\r\n\t\r\n", "说明文字\n名称：not-a-url\n空地址:", "ftp://example.com/a.png\n坏图：https://example.com:0/a.png").forEach { input ->
            val exception = assertThrows(IllegalArgumentException::class.java) { store.addStickers(group.id, input) }
            assertEquals("请至少输入一个 HTTP 或 HTTPS 图片地址", exception.message)
        }

        assertSame(before, store.groups.value)
        assertEquals(persisted, preferences.snapshot())
        assertEquals(beforeEdits, preferences.editCalls)
        assertEquals(beforeCommits, preferences.commitCalls)
    }

    @Test
    fun `a stale deleted group fails safely without resurrecting or saving it`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val deleted = store.addGroup("已删除")
        val remaining = store.addGroup("保留")
        store.deleteGroup(deleted.id)
        val before = store.groups.value
        val persisted = preferences.snapshot()
        val beforeEdits = preferences.editCalls
        val beforeCommits = preferences.commitCalls

        val exception = assertThrows(IllegalArgumentException::class.java) {
            store.addStickers(deleted.id, "$first\n$second")
        }

        assertEquals("分组已不存在，请重新选择", exception.message)
        assertSame(before, store.groups.value)
        assertEquals(listOf(remaining), store.groups.value)
        assertEquals(persisted, preferences.snapshot())
        assertEquals(beforeEdits, preferences.editCalls)
        assertEquals(beforeCommits, preferences.commitCalls)
    }

    @Test
    fun `a whole batch is persisted in one commit before its snapshot is published`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("目标")
        val untouched = store.addGroup("其他")
        val existing = store.addSticker(group.id, first)
        val before = store.groups.value
        val beforeEdits = preferences.editCalls
        val beforeCommits = preferences.commitCalls
        var committed: List<StickerGroup>? = null
        preferences.onCommit = { values ->
            assertSame(before, store.groups.value)
            committed = Json.decodeFromString<List<StickerGroup>>(values.getValue("groups") as String)
        }

        val added = store.addStickers(group.id, "抱抱：$second\nhttps://example.com/third.webp")

        assertEquals(beforeEdits + 1, preferences.editCalls)
        assertEquals(beforeCommits + 1, preferences.commitCalls)
        assertEquals(0, preferences.applyCalls)
        assertEquals(store.groups.value, committed)
        assertEquals(listOf(existing) + added, store.groups.value.first().stickers)
        assertEquals(untouched, store.groups.value.last())
    }

    @Test
    fun `a saved batch survives real JSON serialization and store initialization`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情 \"收藏\"")
        store.addStickers(group.id, "$first\n$second")
        val beforeCommits = preferences.commitCalls

        val restored = newStore(preferences)

        assertEquals(store.groups.value, restored.groups.value)
        assertEquals(beforeCommits, preferences.commitCalls)
        assertNull(restored.error.value)
    }

    @Test
    fun `failed commit never publishes the batch and blocks subsequent writes`() {
        val preferences = MemoryPreferences()
        val store = newStore(preferences)
        val group = store.addGroup("表情")
        val before = store.groups.value
        val persisted = preferences.snapshot()
        val beforeCommits = preferences.commitCalls
        preferences.commitSucceeds = false

        assertThrows(IOException::class.java) { store.addStickers(group.id, "$first\n$second") }

        assertSame(before, store.groups.value)
        assertEquals(persisted, preferences.snapshot())
        assertEquals(beforeCommits + 1, preferences.commitCalls)
        assertNotNull(store.error.value)
        assertThrows(IllegalStateException::class.java) { store.addStickers(group.id, first) }
        assertEquals(beforeCommits + 1, preferences.commitCalls)
        assertTrue(store.groups.value.single().stickers.isEmpty())
    }

    @Test
    fun `legacy JSON without names loads and rename survives restart`() {
        val prefs = MemoryPreferences()
        val groupId = UUID.randomUUID().toString()
        val stickerId = UUID.randomUUID().toString()
        prefs.edit().putString("groups", """[{"id":"$groupId","name":"分组","stickers":[{"id":"$stickerId","url":"$first"}]}]""").commit()
        val store = newStore(prefs)
        assertNull(store.error.value)
        assertEquals("", store.groups.value.single().stickers.single().name)
        store.renameSticker(groupId, stickerId, "  委屈求抱抱  ")
        val saved = newStore(prefs).groups.value.single().stickers.single()
        assertEquals("委屈求抱抱", saved.name)
        assertEquals(first, saved.url)
        assertEquals(stickerId, saved.id)
        store.renameSticker(groupId, stickerId, "")
        assertEquals("", newStore(prefs).groups.value.single().stickers.single().name)
    }

    @Test
    fun `named add and rename target only the selected sticker and reject stale ids`() {
        val store = newStore(MemoryPreferences())
        val group = store.addGroup("分组")
        val named = store.addSticker(group.id, first, "开心")
        val other = store.addSticker(group.id, second, "委屈")
        store.renameSticker(group.id, named.id, "抱抱")
        assertEquals(listOf(named.copy(name = "抱抱"), other), store.groups.value.single().stickers)
        assertThrows(IllegalArgumentException::class.java) { store.renameSticker(group.id, "missing", "名称") }
        assertThrows(IllegalArgumentException::class.java) { store.renameSticker("missing", named.id, "名称") }
    }

    @Test
    fun `name formatting keeps signed URL and neutral unnamed fallback`() {
        assertEquals("[表情包:开心] $first", stickerMessageText(first, " 开心 "))
        assertEquals("[表情包:未命名] $first", stickerMessageText(first, " \n "))
        assertEquals("抱抱 委屈", normalizeStickerName(" [抱抱]\n【委屈】 "))
        assertEquals(80, normalizeStickerName("啊".repeat(90)).length)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        var editCalls = 0
        var commitCalls = 0
        var applyCalls = 0
        var commitSucceeds = true
        var onCommit: ((Map<String, Any?>) -> Unit)? = null

        fun snapshot(): Map<String, Any?> = values.toMap()

        override fun contains(key: String?) = values.containsKey(key)
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as Set<*>?)?.map { it as String }?.toMutableSet() ?: defValues
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor {
            editCalls++
            return object : SharedPreferences.Editor {
                private val pending = mutableMapOf<String, Any?>()
                private val removed = mutableSetOf<String>()
                private var clearAll = false

                private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply {
                    pending[requireNotNull(key)] = value
                    removed -= key
                }
                override fun putString(key: String?, value: String?) = put(key, value)
                override fun putStringSet(key: String?, value: MutableSet<String>?) = put(key, value?.toSet())
                override fun putBoolean(key: String?, value: Boolean) = put(key, value)
                override fun putFloat(key: String?, value: Float) = put(key, value)
                override fun putInt(key: String?, value: Int) = put(key, value)
                override fun putLong(key: String?, value: Long) = put(key, value)
                override fun remove(key: String?): SharedPreferences.Editor = apply {
                    removed += requireNotNull(key)
                    pending -= key
                }
                override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
                override fun apply() {
                    applyCalls++
                    commit()
                }
                override fun commit(): Boolean {
                    commitCalls++
                    val next = if (clearAll) mutableMapOf() else values.toMutableMap()
                    removed.forEach(next::remove)
                    pending.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
                    onCommit?.invoke(next.toMap())
                    if (commitSucceeds) {
                        values.clear()
                        values.putAll(next)
                    }
                    return commitSucceeds
                }
            }
        }
    }
}