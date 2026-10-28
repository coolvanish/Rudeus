package kz.rudy.ar.core.meta

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetaConfigTest {
    @Test
    fun toStringNeverPrintsTheKey() {
        val secret = "not-a-real-secret-value-0123"
        val text = MetaConfig(apiKey = secret).toString()
        assertFalse(secret in text, "MetaConfig.toString leaked the key: $text")
        assertTrue("<hidden>" in text)
    }
}
