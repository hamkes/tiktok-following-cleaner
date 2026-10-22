package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.7 — Language integrity test.
 *
 * Verifies that res/values/strings.xml (Arabic, default) and
 * res/values-en/strings.xml (English) contain EXACTLY the same set of keys.
 * Fails loudly if:
 *  - either file is missing/empty,
 *  - a key exists in one file but not the other,
 *  - a duplicated key is found in either file.
 *
 * This is pure-JVM (no Android types). It parses the XML with a small
 * regex over <string name="..."> entries (sufficient for these two flat
 * resource files; no nested resources are used).
 *
 * NOT BUILT, NOT TESTED. Assumes the project tree is reachable from the
 * test working directory via one of the candidate roots listed below.
 */
class LangIntegrityTest {

    /** Candidate project roots, tried in order. */
    private val roots = listOf(
        File(System.getProperty("user.dir") ?: "."),
        File(System.getProperty("user.dir") ?: ".", ".."),
        File(System.getProperty("user.dir") ?: ".", "../.."),
        File(".")
    )

    private fun findStringsFile(relative: String): File {
        for (root in roots) {
            val f = File(root, relative)
            if (f.exists() && f.isFile) return f
        }
        // Hard failure rather than a silent skip.
        throw AssertionError(
            "Could not locate $relative from user.dir=${System.getProperty("user.dir")}; " +
                "tried: " + roots.joinToString { it.absolutePath }
        )
    }

    private fun extractKeys(file: File): List<String> {
        val text = file.readText()
        val regex = Regex("<string\\s+name=\"([A-Za-z0-9_]+)\"")
        return regex.findAll(text).map { it.groupValues[1] }.toList()
    }

    @Test
    fun arabicStringsFileExists() {
        val f = findStringsFile("app/src/main/res/values/strings.xml")
        assertTrue("Arabic strings.xml must be non-empty", f.readText().isNotBlank())
    }

    @Test
    fun englishStringsFileExists() {
        val f = findStringsFile("app/src/main/res/values-en/strings.xml")
        assertTrue("English strings.xml must be non-empty", f.readText().isNotBlank())
    }

    @Test
    fun noDuplicateKeysInArabic() {
        val keys = extractKeys(findStringsFile("app/src/main/res/values/strings.xml"))
        val dups = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("Duplicate keys in AR strings.xml: $dups", dups.isEmpty())
    }

    @Test
    fun noDuplicateKeysInEnglish() {
        val keys = extractKeys(findStringsFile("app/src/main/res/values-en/strings.xml"))
        val dups = keys.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("Duplicate keys in EN strings.xml: $dups", dups.isEmpty())
    }

    @Test
    fun keySetsAreIdentical() {
        val ar = extractKeys(findStringsFile("app/src/main/res/values/strings.xml")).toSet()
        val en = extractKeys(findStringsFile("app/src/main/res/values-en/strings.xml")).toSet()
        val missingInEn = ar - en
        val missingInAr = en - ar
        assertEquals(
            "Keys present in AR but missing in EN: $missingInEn",
            emptySet<String>(), missingInEn
        )
        assertEquals(
            "Keys present in EN but missing in AR: $missingInAr",
            emptySet<String>(), missingInAr
        )
    }

    @Test
    fun keyCountsMatch() {
        val ar = extractKeys(findStringsFile("app/src/main/res/values/strings.xml"))
        val en = extractKeys(findStringsFile("app/src/main/res/values-en/strings.xml"))
        assertEquals(
            "AR and EN strings files must contain the same number of entries " +
                "(AR=${ar.size}, EN=${en.size})",
            ar.size, en.size
        )
    }

    @Test
    fun appNameIsLocalizedInBoth() {
        val ar = extractKeys(findStringsFile("app/src/main/res/values/strings.xml"))
        val en = extractKeys(findStringsFile("app/src/main/res/values-en/strings.xml"))
        assertTrue("app_name missing in AR", ar.contains("app_name"))
        assertTrue("app_name missing in EN", en.contains("app_name"))
    }
}
