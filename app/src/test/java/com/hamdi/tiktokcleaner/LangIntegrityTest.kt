package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LangIntegrityTest {

    @Test
    fun arabicAndEnglishStringsHaveIdenticalKeys() {
        val arFile = File("src/main/res/values/strings.xml")
        val enFile = File("src/main/res/values-en/strings.xml")
        if (!arFile.exists() || !enFile.exists()) return
        val arKeys = parseKeys(arFile)
        val enKeys = parseKeys(enFile)
        assertEquals(arKeys, enKeys)
    }

    private fun parseKeys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val keys = LinkedHashSet<String>()
        for (i in 0 until nodes.length) {
            val name = nodes.item(i).attributes?.getNamedItem("name")?.nodeValue
            if (!name.isNullOrEmpty()) keys.add(name)
        }
        return keys
    }
}
