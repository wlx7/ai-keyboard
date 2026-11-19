package app.aikb.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class PersonalDictionaryTest {

    @Test
    fun roundTripsGboardZipAndMatchesShortcuts() {
        val dictFile = Files.createTempFile("pdict", ".tsv").toFile()
        val enFile = Files.createTempFile("enhist", ".tsv").toFile()
        val history = UserHistory(enFile)

        PersonalDictionary.loadFromFile(dictFile, force = true)
        PersonalDictionary.addOrUpdateInFile(dictFile, "dz", "上海市浦东新区世纪大道100号", "zh-CN")
        PersonalDictionary.addOrUpdateInFile(dictFile, "omw", "On my way!", "en-US")
        history.importWord("Keylo", minCount = 5)
        history.save()

        val zhMatches = PersonalDictionary.match("dz", chinese = true)
        assertEquals(1, zhMatches.size)
        assertEquals("上海市浦东新区世纪大道100号", zhMatches[0].word)
        assertTrue(zhMatches[0].exactShortcut)

        val enMatches = PersonalDictionary.match("omw", chinese = false)
        assertEquals("On my way!", enMatches.first().word)

        // Export to Gboard-compatible .zip
        val zipBytes = ByteArrayOutputStream().also { out ->
            val exported = PersonalDictionary.exportGboardZip(dictFile, out, enHistory = history)
            assertTrue("Expected >= 3 exported entries, got $exported", exported >= 3)
        }.toByteArray()

        // Re-import into a fresh PersonalDictionary + UserHistory
        val dictFile2 = Files.createTempFile("pdict2", ".tsv").toFile()
        val enFile2 = Files.createTempFile("enhist2", ".tsv").toFile()
        val history2 = UserHistory(enFile2)
        PersonalDictionary.loadFromFile(dictFile2, force = true)
        assertEquals(0, PersonalDictionary.allEntries().size)

        val summary = PersonalDictionary.importFromStream(dictFile2, ByteArrayInputStream(zipBytes), enHistory = history2)
        assertTrue("Expected >= 3 custom entries imported, got ${summary.importedCustom}", summary.importedCustom >= 3)
        assertTrue(history2.count("keylo") >= 3)
        assertEquals("上海市浦东新区世纪大道100号", PersonalDictionary.match("dz", chinese = true).first().word)
    }

    @Test
    fun importsRimeDictYamlFormat() {
        val dictFile = Files.createTempFile("pdict_rime", ".tsv").toFile()
        PersonalDictionary.loadFromFile(dictFile, force = true)
        val rimeYaml = """
            ---
            name: custom_phrase
            version: "1.0"
            ...
            秋水时至	qiu shui shi zhi	100
            韦乐乐	wei le le	50
        """.trimIndent()
        val summary = PersonalDictionary.importFromStream(dictFile, ByteArrayInputStream(rimeYaml.toByteArray(Charsets.UTF_8)))
        assertEquals(2, summary.importedCustom)
        assertEquals("韦乐乐", PersonalDictionary.match("weilele", chinese = true).first().word)
        assertFalse(PersonalDictionary.match("wei", chinese = true).first().exactShortcut)
    }
}
