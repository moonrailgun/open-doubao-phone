package com.openodoubao.assistant.core

/** The Markdown subset model summaries use, flattened to plain text plus style ranges for a TextView. */
data class Markdown(val text: String, val spans: List<Span>) {
    enum class Style { BOLD, ITALIC, CODE, HEADING }
    data class Span(val style: Style, val start: Int, val end: Int)

    companion object {
        // ponytail: line-by-line subset; bold spanning lines, nested emphasis and code fences stay raw. Swap in Markwon if summaries need more.
        private val inline = Regex("""\*\*(.+?)\*\*|`([^`]+)`|\[([^\]]+)]\((\S+?)\)|(?<![\w*])\*(?![\s*])(.+?)(?<![\s*])\*(?![\w*])""")
        private val heading = Regex("""^#{1,6}\s+""")
        private val bullet = Regex("""^(\s*)[-*+]\s+""")
        // Horizontal rules and table header separators carry no text.
        private val rule = Regex("""^\s*(?:[-*_]\s*){3,}$|^\s*\|?\s*:?-{3,}:?\s*(?:\|\s*:?-{3,}:?\s*)*\|?\s*$""")

        fun parse(source: String): Markdown {
            val out = StringBuilder()
            val spans = mutableListOf<Span>()
            source.trim().lines().filterNot { rule.matches(it) }.forEachIndexed { i, raw ->
                if (i > 0) out.append('\n')
                val start = out.length
                var line = raw.replace(heading, "").replace(bullet, "$1• ")
                if (line.trimStart().startsWith("|")) line = line.trim().trim('|').split("|").joinToString("  |  ") { it.trim() }
                var last = 0
                inline.findAll(line).forEach { match ->
                    out.append(line, last, match.range.first)
                    val (bold, code, label, url, italic) = match.destructured
                    val from = out.length
                    when {
                        bold.isNotEmpty() -> out.append(bold).also { spans += Span(Style.BOLD, from, out.length) }
                        code.isNotEmpty() -> out.append(code).also { spans += Span(Style.CODE, from, out.length) }
                        label.isNotEmpty() -> out.append(if (label == url) url else "$label ($url)")
                        else -> out.append(italic).also { spans += Span(Style.ITALIC, from, out.length) }
                    }
                    last = match.range.last + 1
                }
                out.append(line, last, line.length)
                if (heading.containsMatchIn(raw)) spans += Span(Style.HEADING, start, out.length)
            }
            return Markdown(out.toString(), spans)
        }
    }
}
