package dev.scooph.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineSmokeTest {
    @Test
    fun `engine reports its version`() {
        val engine = EngineFacade(File(System.getProperty("java.io.tmpdir"), "scooph-test"))
        assertTrue(engine.version().isNotBlank())
    }
}
