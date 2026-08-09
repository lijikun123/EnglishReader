package com.example.englishreader.data.importer

import android.text.Html
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** 解析出的单章（spine 顺序的可读单元）。 */
data class ParsedChapter(val title: String, val content: String)

/** 真实目录项（来自 nav.xhtml / toc.ncx），已解析到对应 spine 章节索引。 */
data class ParsedTocItem(
    val label: String,
    val href: String,
    val chapterIndex: Int,
    val level: Int,
    val orderIndex: Int,
    /** href 的 fragment 在该章节正文内的段落索引；-1 表示无 fragment 或未命中（跳章节开头）。 */
    val anchorParagraph: Int = -1,
)

data class ParsedEpub(
    val title: String,
    val author: String,
    val chapters: List<ParsedChapter>,
    /** 电子书自带目录（解析失败时为空，调用方回退到 spine 章节列表）。 */
    val toc: List<ParsedTocItem>,
)

class EpubParseException(message: String) : Exception(message)

/**
 * 极简 EPUB 解析器（零额外依赖：JDK zip / DOM + Android Html）。
 *  - spine：得到可读章节 [ParsedChapter] 与「zip 路径 → 章节索引」映射
 *  - 目录：优先 EPUB3 nav.xhtml(epub:type="toc")，其次 EPUB2 toc.ncx，
 *    把每个目录项的 href 解析到对应 spine 章节；都没有时返回空目录（调用方回退）
 *
 * 仅支持无 DRM 的标准 EPUB（EPUB 2 / 3）。出错抛出 [EpubParseException]。
 */
class EpubParser {

    private companion object {
        const val OPS_NS = "http://www.idpf.org/2007/ops"
    }

    private data class ManifestItem(val href: String, val mediaType: String, val properties: String)
    private data class RawToc(val label: String, val href: String, val level: Int)

    fun parse(file: File): ParsedEpub {
        ZipFile(file).use { zip ->
            val containerEntry = zip.getEntry("META-INF/container.xml")
                ?: throw EpubParseException("不是有效的 EPUB：缺少 META-INF/container.xml")
            val opfPath = readOpfPath(zip, containerEntry)
                ?: throw EpubParseException("无法在 EPUB 中定位 OPF 文件")
            val opfDoc = (zip.getEntry(opfPath) ?: throw EpubParseException("无法读取 OPF 文件"))
                .let { entry -> zip.getInputStream(entry).use { parseXml(it) } }
            val opfDir = opfPath.substringBeforeLast('/', "")

            val title = firstText(opfDoc, "title")
            val author = firstText(opfDoc, "creator")
            val manifest = readManifest(opfDoc)

            // spine → 章节 + 路径映射 + 每章锚点(id/name → 段落索引)
            val pathToIndex = LinkedHashMap<String, Int>()
            val chapters = ArrayList<ParsedChapter>()
            val chapterAnchors = ArrayList<Map<String, Int>>()
            for (idref in spineIdrefs(opfDoc)) {
                val item = manifest[idref] ?: continue
                val media = item.mediaType.lowercase()
                if (media.isNotEmpty() && !media.contains("html")) continue
                val path = resolvePath(opfDir, item.href)
                val entry = zip.getEntry(path) ?: zip.getEntry(item.href) ?: continue
                val html = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val (text, anchors) = htmlToTextWithAnchors(html)
                if (text.isBlank()) continue
                val index = chapters.size
                chapters.add(ParsedChapter(extractTitle(html) ?: "Chapter ${index + 1}", text))
                pathToIndex[path] = index
                chapterAnchors.add(anchors) // 与 chapters 索引对齐
            }
            if (chapters.isEmpty()) throw EpubParseException("EPUB 中未找到可读章节")

            // 目录解析失败绝不影响导入：捕获异常并回退到空目录。
            val toc = runCatching { parseToc(zip, opfDoc, opfDir, manifest, pathToIndex, chapterAnchors) }
                .getOrDefault(emptyList())

            return ParsedEpub(title = title, author = author, chapters = chapters, toc = toc)
        }
    }

    // ---------------- spine / manifest ----------------

    private fun spineIdrefs(opf: Document): List<String> {
        val result = ArrayList<String>()
        val itemrefs = opf.getElementsByTagNameNS("*", "itemref")
        for (i in 0 until itemrefs.length) {
            val el = itemrefs.item(i) as? Element ?: continue
            if (el.getAttribute("linear").equals("no", ignoreCase = true)) continue
            val idref = el.getAttribute("idref")
            if (idref.isNotEmpty()) result.add(idref)
        }
        return result
    }

    private fun readManifest(opf: Document): Map<String, ManifestItem> {
        val map = HashMap<String, ManifestItem>()
        val items = opf.getElementsByTagNameNS("*", "item")
        for (i in 0 until items.length) {
            val el = items.item(i) as? Element ?: continue
            val id = el.getAttribute("id")
            val href = el.getAttribute("href")
            if (id.isNotEmpty() && href.isNotEmpty()) {
                map[id] = ManifestItem(href, el.getAttribute("media-type"), el.getAttribute("properties"))
            }
        }
        return map
    }

    // ---------------- TOC ----------------

    private fun parseToc(
        zip: ZipFile,
        opf: Document,
        opfDir: String,
        manifest: Map<String, ManifestItem>,
        pathToIndex: Map<String, Int>,
        chapterAnchors: List<Map<String, Int>>,
    ): List<ParsedTocItem> {
        // 1) EPUB3 nav.xhtml
        manifest.values.firstOrNull { it.properties.split(" ").any { p -> p == "nav" } }?.let { navItem ->
            readDoc(zip, opfDir, navItem.href)?.let { (doc, dir) ->
                val resolved = resolveToc(parseNavDoc(doc), dir, pathToIndex, chapterAnchors)
                if (resolved.isNotEmpty()) return resolved
            }
        }
        // 2) EPUB2 toc.ncx
        findNcxItem(opf, manifest)?.let { ncxItem ->
            readDoc(zip, opfDir, ncxItem.href)?.let { (doc, dir) ->
                val resolved = resolveToc(parseNcxDoc(doc), dir, pathToIndex, chapterAnchors)
                if (resolved.isNotEmpty()) return resolved
            }
        }
        return emptyList()
    }

    /** 读取某 href 指向的 XML，返回 (文档, 该文件所在目录)。 */
    private fun readDoc(zip: ZipFile, opfDir: String, href: String): Pair<Document, String>? {
        val path = resolvePath(opfDir, href)
        val entry = zip.getEntry(path) ?: zip.getEntry(href) ?: return null
        val doc = zip.getInputStream(entry).use { parseXml(it) }
        return doc to path.substringBeforeLast('/', "")
    }

    private fun resolveToc(
        raw: List<RawToc>,
        tocDir: String,
        pathToIndex: Map<String, Int>,
        chapterAnchors: List<Map<String, Int>>,
    ): List<ParsedTocItem> {
        val out = ArrayList<ParsedTocItem>()
        var order = 0
        for (item in raw) {
            if (item.label.isBlank() || item.href.isBlank()) continue
            val target = resolvePath(tocDir, item.href.substringBefore('#'))
            val chapterIndex = pathToIndex[target] ?: continue
            // fragment → 章节内段落索引（URL decode；未命中则 -1，点击时回退章节开头）
            val fragment = item.href.substringAfter('#', "")
            val anchorParagraph = if (fragment.isNotEmpty()) {
                val decoded = runCatching { URLDecoder.decode(fragment, "UTF-8") }.getOrDefault(fragment)
                chapterAnchors.getOrNull(chapterIndex)?.get(decoded) ?: -1
            } else {
                -1
            }
            out.add(ParsedTocItem(item.label, item.href, chapterIndex, item.level, order++, anchorParagraph))
        }
        return out
    }

    private fun parseNavDoc(doc: Document): List<RawToc> {
        val navs = doc.getElementsByTagNameNS("*", "nav")
        var tocNav: Element? = null
        for (i in 0 until navs.length) {
            val el = navs.item(i) as? Element ?: continue
            val type = el.getAttributeNS(OPS_NS, "type").ifEmpty { el.getAttribute("epub:type") }
            if (type.split(" ").any { it.equals("toc", ignoreCase = true) }) {
                tocNav = el
                break
            }
        }
        if (tocNav == null && navs.length > 0) tocNav = navs.item(0) as? Element
        val nav = tocNav ?: return emptyList()
        val ol = directChild(nav, "ol") ?: return emptyList()
        val out = ArrayList<RawToc>()
        walkOl(ol, 0, out)
        return out
    }

    private fun walkOl(ol: Element, level: Int, out: MutableList<RawToc>) {
        for (li in directChildren(ol, "li")) {
            var label = ""
            var href = ""
            var nested: Element? = null
            for (child in childElements(li)) {
                when (localName(child)) {
                    "a" -> if (href.isEmpty()) {
                        href = child.getAttribute("href")
                        label = textContent(child)
                    }
                    "span" -> if (label.isEmpty()) label = textContent(child)
                    "ol" -> nested = child
                }
            }
            if (label.isNotBlank() && href.isNotBlank()) out.add(RawToc(label, href, level))
            nested?.let { walkOl(it, level + 1, out) }
        }
    }

    private fun parseNcxDoc(doc: Document): List<RawToc> {
        val maps = doc.getElementsByTagNameNS("*", "navMap")
        val navMap = (0 until maps.length).firstNotNullOfOrNull { maps.item(it) as? Element }
            ?: return emptyList()
        val out = ArrayList<RawToc>()
        for (np in directChildren(navMap, "navPoint")) walkNavPoint(np, 0, out)
        return out
    }

    private fun walkNavPoint(np: Element, level: Int, out: MutableList<RawToc>) {
        val label = directChildren(np, "navLabel").firstOrNull()
            ?.let { directChildren(it, "text").firstOrNull() }
            ?.let { textContent(it) }
            .orEmpty()
        val src = directChildren(np, "content").firstOrNull()?.getAttribute("src").orEmpty()
        if (label.isNotBlank() && src.isNotBlank()) out.add(RawToc(label, src, level))
        for (child in directChildren(np, "navPoint")) walkNavPoint(child, level + 1, out)
    }

    private fun findNcxItem(opf: Document, manifest: Map<String, ManifestItem>): ManifestItem? {
        val spines = opf.getElementsByTagNameNS("*", "spine")
        val tocId = (0 until spines.length)
            .firstNotNullOfOrNull { (spines.item(it) as? Element)?.getAttribute("toc")?.takeIf(String::isNotEmpty) }
        if (tocId != null) manifest[tocId]?.let { return it }
        return manifest.values.firstOrNull { it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) }
    }

    // ---------------- XML / DOM helpers ----------------

    private fun readOpfPath(zip: ZipFile, entry: ZipEntry): String? {
        val doc = zip.getInputStream(entry).use { parseXml(it) }
        val roots = doc.getElementsByTagNameNS("*", "rootfile")
        for (i in 0 until roots.length) {
            val el = roots.item(i) as? Element ?: continue
            val path = el.getAttribute("full-path")
            if (path.isNotEmpty()) return path
        }
        return null
    }

    private fun parseXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(input)
    }

    private fun firstText(doc: Document, localName: String): String {
        val nodes = doc.getElementsByTagNameNS("*", localName)
        if (nodes.length == 0) return ""
        return nodes.item(0)?.textContent?.trim().orEmpty()
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val cleanHref = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(cleanHref, "UTF-8") }.getOrDefault(cleanHref)
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val parts = ArrayList<String>()
        for (seg in combined.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun extractTitle(html: String): String? {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val raw = Regex("<h[1-6][^>]*>(.*?)</h[1-6]>", opts).find(html)?.groupValues?.get(1)
            ?: Regex("<title[^>]*>(.*?)</title>", opts).find(html)?.groupValues?.get(1)
        // 去掉图片占位符 ￼(U+FFFC) 与 BOM，避免标题里出现「方块/OBJ」
        return raw?.let { fromHtml(it) }?.replace("￼", "")?.replace("﻿", "")?.trim()?.takeIf { it.isNotBlank() }
    }

    // 锚点标记用私有区字符包裹 id，Html.fromHtml 会原样保留为文本，便于事后定位再剔除。
    private val anchorMarker = Regex("(.*?)")
    private val anchorTag =
        Regex("<\\w+\\b[^>]*?\\s(?:id|name)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>", RegexOption.IGNORE_CASE)

    /**
     * XHTML/HTML → 纯文本，同时返回「id/name 锚点 → 段落索引」映射。
     * 做法：在带 id/name 的开始标签后注入标记 → 转纯文本 → 按与阅读器一致的方式切段 →
     * 标记落在哪一段即为该锚点的段落索引，最后从正文剔除标记。
     */
    private fun htmlToTextWithAnchors(html: String): Pair<String, Map<String, Int>> {
        val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        var body = html
        body = Regex("<head[^>]*>.*?</head>", opts).replace(body, " ")
        body = Regex("<style[^>]*>.*?</style>", opts).replace(body, " ")
        body = Regex("<script[^>]*>.*?</script>", opts).replace(body, " ")
        body = anchorTag.replace(body) { m -> m.value + "" + m.groupValues[1] + "" }

        val marked = fromHtml(body)
            // 去掉图片/对象占位符（OBJECT REPLACEMENT CHARACTER）与 BOM，
            // 这样「仅含封面图片」的页面会变成空白 → 被当作非正文章节跳过。
            .replace("￼", "")
            .replace("﻿", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

        val cleanParas = ArrayList<String>()
        val anchors = HashMap<String, Int>()
        for (block in marked.split(Regex("\\n\\s*\\n"))) {
            // 该段（若非空）将获得的索引；若为空，则归到下一非空段
            for (mm in anchorMarker.findAll(block)) anchors[mm.groupValues[1]] = cleanParas.size
            val cleaned = block.replace(anchorMarker, "").trim()
            if (cleaned.isNotEmpty()) cleanParas.add(cleaned)
        }
        return cleanParas.joinToString("\n\n") to anchors
    }

    private fun fromHtml(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString()

    private fun childElements(node: Node): List<Element> =
        (0 until node.childNodes.length).mapNotNull { node.childNodes.item(it) as? Element }

    private fun directChildren(node: Node, local: String): List<Element> =
        childElements(node).filter { localName(it) == local }

    private fun directChild(node: Node, local: String): Element? = directChildren(node, local).firstOrNull()

    private fun localName(el: Element): String = el.localName ?: el.tagName.substringAfterLast(':')

    private fun textContent(el: Element): String = el.textContent?.trim().orEmpty()
}
