package labs.ranikun.finance.localholding

import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Deliberately local/test-only, opt-in file store; never a production persistence contract. */
class LocalHoldingStore(private val directory: Path) {
    init {
        require(directory.isAbsolute && directory.normalize() == directory)
        Files.createDirectories(directory)
        require(directory.toRealPath() == directory) { "Local holding directory must not contain symbolic links" }
    }

    private fun ownerFile(owner: String): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(StandardCharsets.UTF_8))
        return directory.resolve(digest.joinToString("") { "%02x".format(it) } + ".txt")
    }

    @Synchronized fun read(owner: String): String? {
        val file = ownerFile(owner)
        require(!Files.isSymbolicLink(file)) { "Invalid local holding file" }
        if (!Files.exists(file)) return null
        return Files.readString(file).takeIf { TICKER.matches(it) }
            ?: throw IllegalStateException("Invalid local holding data")
    }

    @Synchronized fun save(owner: String, ticker: String) {
        require(TICKER.matches(ticker))
        require(!Files.isSymbolicLink(ownerFile(owner))) { "Invalid local holding file" }
        val temp = Files.createTempFile(directory, "holding-", ".tmp")
        try {
            Files.writeString(temp, ticker)
            Files.move(temp, ownerFile(owner), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    companion object { val TICKER = Regex("^[A-Z][A-Z0-9.-]{0,9}$") }
}

data class LocalHoldingRequest(val ticker: String)
data class LocalHoldingResponse(val ticker: String?)
data class SyntheticFiling(val ticker: String, val source: String, val asOf: String, val period: String, val notice: String)

@RestController
@Profile("!prod & !production & (local | test)")
@ConditionalOnProperty(prefix = "finance.local-holding", name = ["enabled"], havingValue = "true")
@RequestMapping("/finance/local-holding")
class LocalHoldingController(
    private val currentUser: CurrentUserPort,
    @Value("\${finance.local-holding.directory}") directory: String,
    @Value("\${server.address:}") address: String,
) {
    init { require(address == "127.0.0.1" || address == "::1") { "Local holding requires an explicit loopback server.address" } }
    private val store = LocalHoldingStore(Path.of(directory).also { require(it.isAbsolute) })

    @GetMapping fun get() = LocalHoldingResponse(store.read(currentUser.currentUserId().value))

    @PutMapping fun put(@RequestBody request: LocalHoldingRequest): LocalHoldingResponse {
        if (!LocalHoldingStore.TICKER.matches(request.ticker)) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        store.save(currentUser.currentUserId().value, request.ticker)
        return LocalHoldingResponse(request.ticker)
    }

    @GetMapping("/filing") fun filing(): SyntheticFiling {
        val ticker = store.read(currentUser.currentUserId().value) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return SyntheticFiling(ticker, "local-synthetic-fixture:v1", "2026-01-01T00:00:00Z", "fictional period", "Synthetic example; not an official filing or issuer information.")
    }
}
