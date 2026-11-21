package labs.ranikun.finance.holding

import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import jakarta.servlet.http.HttpServletRequest
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HOLDING_PATH
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

data class HoldingResponse(val ticker: String?)

@RestController
@Profile("finance-trusted-context-reference")
@ConditionalOnProperty(prefix = "finance.holding.reference", name = ["enabled"], havingValue = "true")
@RequestMapping(HOLDING_PATH)
class HoldingController(
    private val currentUser: CurrentUserPort,
    @Value("\${finance.holding.reference.directory}") directory: String,
) {
    private val store = LocalHoldingStore(Path.of(directory))

    @GetMapping
    fun get(request: HttpServletRequest): ResponseEntity<HoldingResponse> {
        requireNoTarget(request)
        return response(store.read(currentUser.currentUserId().value))
    }

    @PutMapping
    fun put(request: HttpServletRequest, @RequestBody body: Map<String, Any?>): ResponseEntity<HoldingResponse> {
        requireNoTarget(request)
        val ticker = body["ticker"] as? String
        if (body.keys != setOf("ticker") || ticker == null || !LocalHoldingStore.TICKER.matches(ticker)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        }
        store.save(currentUser.currentUserId().value, ticker)
        return response(ticker)
    }

    private fun requireNoTarget(request: HttpServletRequest) {
        if (request.queryString != null) throw ResponseStatusException(HttpStatus.BAD_REQUEST)
    }

    private fun response(ticker: String?): ResponseEntity<HoldingResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(HoldingResponse(ticker))
}
