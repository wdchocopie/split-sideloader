package com.sideload.splitinstaller.core.axml

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Engines whose apps cannot start at all without their native libraries. */
enum class NativeEngine(val label: String) {
    UNITY("Unity"),
    UNREAL("Unreal Engine"),
    COCOS("Cocos2d-x"),
    GODOT("Godot"),
    FLUTTER("Flutter"),
    REACT_NATIVE("React Native"),
    XAMARIN(".NET / Xamarin"),
    NATIVE_ACTIVITY("NativeActivity"),
}

/** What an APK's binary manifest tells us about its role in a bundle. */
data class ApkManifest(
    val packageName: String? = null,
    val versionCode: Long = 0,
    val versionName: String? = null,
    val splitName: String? = null,
    val configForSplit: String? = null,
    val isFeatureSplit: Boolean = false,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    /** bundletool marks a base that cannot run without its config splits. */
    val isSplitRequired: Boolean = false,
    /** Android 13+: split types the base declares it needs, e.g. `base__abi,base__density`. */
    val requiredSplitTypes: String? = null,
    /** `<application android:extractNativeLibs>`; null when the manifest leaves it unset. */
    val extractNativeLibs: Boolean? = null,
    /** Play's `com.android.vending.splits.required` meta-data. */
    val vendingSplitsRequired: Boolean = false,
    val engine: NativeEngine? = null,
) {
    val isBase: Boolean get() = splitName.isNullOrEmpty()

    /** True when the manifest itself says the app will not run from a lone base APK. */
    val declaresSplitsRequired: Boolean
        get() = isSplitRequired || vendingSplitsRequired || !requiredSplitTypes.isNullOrBlank()

    val requiresAbiSplit: Boolean
        get() = requiredSplitTypes?.split(',')?.any { it.trim().endsWith("__abi") } == true
}

/**
 * Just enough of Android's binary XML format to read a split APK's identity.
 *
 * The bundle's own `manifest.json` / `info.json` is a convenience, not a contract: it can
 * be absent (plain `.apks`), stale, or wrong. The APK's own manifest is the only source
 * that cannot disagree with what actually gets installed, so it wins whenever it parses.
 */
object AxmlParser {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_RESOURCE_MAP = 0x0180
    private const val RES_XML_START_ELEMENT = 0x0102

    private const val UTF8_FLAG = 1 shl 8

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    // Resource ids, used when a build strips attribute name strings.
    private const val ATTR_NAME = 0x01010003
    private const val ATTR_VALUE = 0x01010024
    private const val ATTR_TARGET_ACTIVITY = 0x01010202
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_TARGET_SDK = 0x01010270
    private const val ATTR_IS_FEATURE_SPLIT = 0x0101055b
    private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea
    private const val ATTR_IS_SPLIT_REQUIRED = 0x01010591

    private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

    fun parse(bytes: ByteArray): ApkManifest {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8) throw IOException("manifest too small")
        if ((b.getShort(0).toInt() and 0xFFFF) != RES_XML) throw IOException("not a binary XML document")

        var strings: List<String> = emptyList()
        var resourceMap = IntArray(0)
        var result = ApkManifest()
        var sawManifest = false
        val classNames = ArrayList<String>()
        val metaNames = ArrayList<String>()

        var p = b.getShort(2).toInt() and 0xFFFF // skip the file header
        if (p < 8) p = 8

        while (p + 8 <= bytes.size) {
            val type = b.getShort(p).toInt() and 0xFFFF
            val headerSize = b.getShort(p + 2).toInt() and 0xFFFF
            val chunkSize = b.getInt(p + 4)
            if (chunkSize <= 0 || p + chunkSize > bytes.size) break

            when (type) {
                RES_STRING_POOL -> strings = readStringPool(b, bytes, p, headerSize)

                RES_XML_RESOURCE_MAP -> {
                    val count = (chunkSize - headerSize) / 4
                    resourceMap = IntArray(count) { b.getInt(p + headerSize + it * 4) }
                }

                RES_XML_START_ELEMENT -> {
                    val tag = strings.getOrNull(b.getInt(p + 20)).orEmpty()
                    when (tag) {
                        "manifest" -> {
                            sawManifest = true
                            val a = readAttributes(b, p, strings, resourceMap)
                            result = result.copy(
                                packageName = a.string("package") ?: result.packageName,
                                versionCode = a.int("versionCode", ATTR_VERSION_CODE) ?: result.versionCode,
                                versionName = a.string("versionName", ATTR_VERSION_NAME) ?: result.versionName,
                                splitName = a.string("split") ?: result.splitName,
                                configForSplit = a.string("configForSplit") ?: result.configForSplit,
                                isFeatureSplit = a.bool("isFeatureSplit", ATTR_IS_FEATURE_SPLIT) ?: false,
                                isSplitRequired = a.bool("isSplitRequired", ATTR_IS_SPLIT_REQUIRED) ?: false,
                                requiredSplitTypes = a.string("requiredSplitTypes"),
                            )
                        }
                        "uses-sdk" -> {
                            val a = readAttributes(b, p, strings, resourceMap)
                            result = result.copy(
                                minSdk = (a.int("minSdkVersion", ATTR_MIN_SDK) ?: 0L).toInt(),
                                targetSdk = (a.int("targetSdkVersion", ATTR_TARGET_SDK) ?: 0L).toInt(),
                            )
                        }
                        "application" -> {
                            val a = readAttributes(b, p, strings, resourceMap)
                            result = result.copy(
                                extractNativeLibs = a.bool("extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS),
                            )
                            a.string("name", ATTR_NAME)?.let(classNames::add)
                        }
                        "meta-data" -> {
                            val a = readAttributes(b, p, strings, resourceMap)
                            val name = a.string("name", ATTR_NAME)
                            if (name != null) {
                                metaNames += name
                                if (name == "com.android.vending.splits.required" &&
                                    (a.bool("value", ATTR_VALUE) == true || a.string("value", ATTR_VALUE) == "true")
                                ) {
                                    result = result.copy(vendingSplitsRequired = true)
                                }
                            }
                        }
                        in COMPONENT_TAGS -> {
                            val a = readAttributes(b, p, strings, resourceMap)
                            a.string("name", ATTR_NAME)?.let(classNames::add)
                            a.string("targetActivity", ATTR_TARGET_ACTIVITY)?.let(classNames::add)
                        }
                    }
                }
            }
            p += chunkSize
        }

        if (!sawManifest) throw IOException("no <manifest> element found")
        return result.copy(engine = detectEngine(classNames, metaNames))
    }

    private fun detectEngine(classes: List<String>, meta: List<String>): NativeEngine? {
        fun any(prefix: String) = classes.any { it.startsWith(prefix) } || meta.any { it.startsWith(prefix) }
        return when {
            any("com.unity3d.player") || meta.any { it.startsWith("unity.") } -> NativeEngine.UNITY
            any("com.epicgames.unreal") || any("com.epicgames.ue4") -> NativeEngine.UNREAL
            any("org.cocos2dx") || any("com.cocos") -> NativeEngine.COCOS
            any("org.godotengine") -> NativeEngine.GODOT
            any("io.flutter") -> NativeEngine.FLUTTER
            any("com.facebook.react") -> NativeEngine.REACT_NATIVE
            any("mono.MonoRuntimeProvider") || any("crc64") -> NativeEngine.XAMARIN
            classes.any { it == "android.app.NativeActivity" } || meta.any { it == "android.app.lib_name" } ->
                NativeEngine.NATIVE_ACTIVITY
            else -> null
        }
    }

    // ---- attributes --------------------------------------------------------

    private class Attrs(
        private val byName: Map<String, Any>,
        private val byResId: Map<Int, Any>,
    ) {
        private fun raw(name: String, resId: Int): Any? = byName[name] ?: byResId[resId]

        fun string(name: String, resId: Int = 0): String? = raw(name, resId) as? String

        fun int(name: String, resId: Int = 0): Long? {
            val v = raw(name, resId) ?: return null
            return v as? Long ?: (v as? String)?.toLongOrNull()
        }

        fun bool(name: String, resId: Int = 0): Boolean? = when (val v = raw(name, resId)) {
            is Long -> v != 0L
            is String -> v.equals("true", ignoreCase = true)
            else -> null
        }
    }

    private fun readAttributes(
        b: ByteBuffer,
        chunkStart: Int,
        strings: List<String>,
        resourceMap: IntArray,
    ): Attrs {
        // ResXMLTree_node is 16 bytes; attributeStart is measured from the attrExt that follows.
        val ext = chunkStart + 16
        val attrStart = b.getShort(ext + 8).toInt() and 0xFFFF
        val attrSize = b.getShort(ext + 10).toInt() and 0xFFFF
        val attrCount = b.getShort(ext + 12).toInt() and 0xFFFF
        if (attrSize < 20 || attrCount == 0) return Attrs(emptyMap(), emptyMap())

        val byName = HashMap<String, Any>(attrCount)
        val byResId = HashMap<Int, Any>(attrCount)

        for (i in 0 until attrCount) {
            val a = ext + attrStart + i * attrSize
            if (a + 20 > b.limit()) break
            val nameIdx = b.getInt(a + 4)
            val rawIdx = b.getInt(a + 8)
            val dataType = b.get(a + 15).toInt() and 0xFF
            val data = b.getInt(a + 16)

            val value: Any? = when {
                rawIdx >= 0 -> strings.getOrNull(rawIdx)
                dataType == TYPE_STRING -> strings.getOrNull(data)
                dataType == TYPE_INT_DEC || dataType == TYPE_INT_HEX -> data.toLong() and 0xFFFFFFFFL
                dataType == TYPE_INT_BOOLEAN -> if (data != 0) 1L else 0L
                else -> null
            }
            if (value == null) continue

            strings.getOrNull(nameIdx)?.takeIf { it.isNotEmpty() }?.let { byName[it] = value }
            resourceMap.getOrNull(nameIdx)?.takeIf { it != 0 }?.let { byResId[it] = value }
        }
        return Attrs(byName, byResId)
    }

    // ---- string pool -------------------------------------------------------

    private fun readStringPool(
        b: ByteBuffer,
        bytes: ByteArray,
        chunkStart: Int,
        headerSize: Int,
    ): List<String> {
        val count = b.getInt(chunkStart + 8)
        val flags = b.getInt(chunkStart + 16)
        val stringsStart = b.getInt(chunkStart + 20)
        if (count <= 0 || count > 1_000_000) return emptyList()

        val utf8 = (flags and UTF8_FLAG) != 0
        val dataBase = chunkStart + stringsStart
        val offsetsAt = chunkStart + headerSize

        return List(count) { i ->
            val offAt = offsetsAt + i * 4
            if (offAt + 4 > bytes.size) return@List ""
            val at = dataBase + b.getInt(offAt)
            if (at < 0 || at >= bytes.size) return@List ""
            runCatching {
                if (utf8) readUtf8(bytes, at) else readUtf16(bytes, b, at)
            }.getOrDefault("")
        }
    }

    /** UTF-8 pool entries carry a character count then a byte count, each possibly 2 bytes. */
    private fun readUtf8(bytes: ByteArray, at: Int): String {
        var p = at
        p += lenSizeUtf8(bytes, p)          // character count, unused
        val byteLen = readLenUtf8(bytes, p)
        p += lenSizeUtf8(bytes, p)
        if (p + byteLen > bytes.size) return ""
        return String(bytes, p, byteLen, Charsets.UTF_8)
    }

    private fun lenSizeUtf8(bytes: ByteArray, p: Int): Int =
        if ((bytes[p].toInt() and 0x80) != 0) 2 else 1

    private fun readLenUtf8(bytes: ByteArray, p: Int): Int {
        val first = bytes[p].toInt() and 0xFF
        return if ((first and 0x80) != 0) {
            ((first and 0x7F) shl 8) or (bytes[p + 1].toInt() and 0xFF)
        } else first
    }

    /** UTF-16 pool entries carry a code-unit count, possibly spread over two 16-bit words. */
    private fun readUtf16(bytes: ByteArray, b: ByteBuffer, at: Int): String {
        var p = at
        var len = b.getShort(p).toInt() and 0xFFFF
        p += 2
        if ((len and 0x8000) != 0) {
            len = ((len and 0x7FFF) shl 16) or (b.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        val byteLen = len * 2
        if (p + byteLen > bytes.size) return ""
        return String(bytes, p, byteLen, Charsets.UTF_16LE)
    }
}
