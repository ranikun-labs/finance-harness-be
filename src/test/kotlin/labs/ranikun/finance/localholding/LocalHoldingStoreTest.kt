package labs.ranikun.finance.localholding

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.web.server.ResponseStatusException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class LocalHoldingStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `owners remain isolated across store reopen and repeated saves`() {
        val store = LocalHoldingStore(directory)
        store.save("owner-a", "AAPL")
        store.save("owner-a", "AAPL")
        store.save("owner-b", "MSFT")
        val reopened = LocalHoldingStore(directory)
        assertEquals("AAPL", reopened.read("owner-a"))
        assertEquals("MSFT", reopened.read("owner-b"))
        assertNull(reopened.read("owner-c"))
        assertFailsWith<IllegalArgumentException> { reopened.save("owner-a", "../BAD") }
        assertEquals("AAPL", reopened.read("owner-a"))
    }
    @Test fun `controller derives owner and binds fixture to saved ticker`() {
        var owner = "a"
        val currentUser = object : CurrentUserPort { override fun currentUserId() = IdentityUserId(owner) }
        assertFailsWith<IllegalArgumentException> { LocalHoldingController(currentUser, directory.toString(), "0.0.0.0") }
        assertFailsWith<IllegalArgumentException> { LocalHoldingController(currentUser, directory.toString(), "") }
        val controller = LocalHoldingController(currentUser, directory.toString(), "127.0.0.1")
        controller.put(LocalHoldingRequest("AAPL"))
        assertEquals("AAPL", controller.filing().ticker)
        owner = "b"
        assertNull(controller.get().ticker)
        assertFailsWith<ResponseStatusException> { controller.filing() }
        controller.put(LocalHoldingRequest("MSFT"))
        assertEquals("MSFT", controller.filing().ticker)
        owner = "a"
        assertEquals("AAPL", controller.get().ticker)
        assertFailsWith<ResponseStatusException> { controller.put(LocalHoldingRequest("aapl")) }
        assertEquals("AAPL", controller.get().ticker)
    }
}
