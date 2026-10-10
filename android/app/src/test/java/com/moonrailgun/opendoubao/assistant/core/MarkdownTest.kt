package com.moonrailgun.opendoubao.assistant.core

import com.moonrailgun.opendoubao.assistant.core.Markdown.Style.*
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun summaryMarkdownBecomesStyledPlainText() {
        val md = Markdown.parse("""
            ## 结论
            **多数人**更值得买 *Pro*
            - 价格：`9999`
            |型号|价格|
            |---|:---:|
            |A|1|
            ---
            [来源](https://a.com) **没闭合 a * b
        """.trimIndent())
        assertEquals("结论\n多数人更值得买 Pro\n• 价格：9999\n型号  |  价格\nA  |  1\n来源 (https://a.com) **没闭合 a * b", md.text)
        assertEquals(listOf(HEADING to "结论", BOLD to "多数人", ITALIC to "Pro", CODE to "9999"),
            md.spans.map { it.style to md.text.substring(it.start, it.end) })
    }
}
