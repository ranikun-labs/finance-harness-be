package labs.ranikun.finance.journal.web

import jakarta.validation.Valid
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalTimeResolver
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

data class JournalCreateResponse(val journalId: String)

@RestController
@RequestMapping("/finance/journals")
class JournalController(
    private val journalCreateApplicationService: JournalCreateApplicationService,
    private val journalTimeResolver: JournalTimeResolver,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@Valid @RequestBody request: JournalCreateRequest): ResponseEntity<JournalCreateResponse> {
        val journalId = journalCreateApplicationService.create(request.toCommand(journalTimeResolver))
        return ResponseEntity
            .created(URI.create("/finance/journals/$journalId"))
            .body(JournalCreateResponse(journalId.toString()))
    }
}
