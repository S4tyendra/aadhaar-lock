package `in`.devh.aadhaarlock

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    private val password = "correct horse battery"
    private val plain = "secret aadhaar data".toByteArray()

    private fun edited(file: ByteArray, change: JSONObject.() -> Unit) =
        JSONObject(String(file)).apply(change).toString().toByteArray()

    @Test fun roundTrip() = assertArrayEquals(plain, BackupCrypto.open(BackupCrypto.seal(plain, password), password))

    @Test fun plainTextIsNotInTheFile() = assertFalse(String(BackupCrypto.seal(plain, password)).contains("secret"))

    @Test fun samePasswordGivesDifferentFiles() =
        assertNotEquals(String(BackupCrypto.seal(plain, password)), String(BackupCrypto.seal(plain, password)))

    @Test fun wrongPasswordFails() {
        val file = BackupCrypto.seal(plain, password)
        val e = assertThrows(BackupCrypto.BadBackup::class.java) { BackupCrypto.open(file, "correct horse battery!") }
        assertEquals("Wrong password, or the backup is damaged", e.message)
    }

    @Test fun editedDataFails() {
        val file = edited(BackupCrypto.seal(plain, password)) {
            val data = getString("data")
            put("data", (if (data[0] == 'A') "B" else "A") + data.drop(1))
        }
        assertThrows(BackupCrypto.BadBackup::class.java) { BackupCrypto.open(file, password) }
    }

    @Test fun weakerSettingsInTheFileFail() {
        val file = edited(BackupCrypto.seal(plain, password)) { put("iterations", 1) }
        assertThrows(BackupCrypto.BadBackup::class.java) { BackupCrypto.open(file, password) }
    }

    @Test fun hostileMemorySettingIsRefused() {
        val file = edited(BackupCrypto.seal(plain, password)) { put("memoryKib", Int.MAX_VALUE) }
        val e = assertThrows(BackupCrypto.BadBackup::class.java) { BackupCrypto.open(file, password) }
        assertEquals("This backup is damaged", e.message)
    }

    @Test fun otherFilesAreRejected() {
        assertThrows(BackupCrypto.BadBackup::class.java) { BackupCrypto.open("hello".toByteArray(), password) }
        assertThrows(BackupCrypto.BadBackup::class.java) { BackupCrypto.open("{}".toByteArray(), password) }
    }

    @Test fun passwordLengthRules() {
        assertFalse(BackupCrypto.validPassword("a".repeat(11)))
        assertTrue(BackupCrypto.validPassword("a".repeat(12)))
        assertTrue(BackupCrypto.validPassword("a".repeat(256)))
        assertFalse(BackupCrypto.validPassword("a".repeat(257)))
        assertTrue("no classes required", BackupCrypto.validPassword("all lowercase"))
    }

    @Test fun emojiCountsAsOneCharacter() {
        assertFalse(BackupCrypto.validPassword("😀".repeat(11)))
        assertTrue(BackupCrypto.validPassword("😀".repeat(12)))
    }

    @Test fun composedAndDecomposedPasswordsMatch() {
        val file = BackupCrypto.seal(plain, "café long password")
        assertArrayEquals(plain, BackupCrypto.open(file, "café long password"))
    }
}
