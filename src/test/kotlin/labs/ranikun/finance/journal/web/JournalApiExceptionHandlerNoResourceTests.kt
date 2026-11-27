package labs.ranikun.finance.journal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class JournalApiExceptionHandlerNoResourceTests {

    @Test
    fun unmatchedResourceIsMappedToNotFoundNotInternalError() {
        val response = JournalApiExceptionHandler().handleNoResource()

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body?.code).isEqualTo("not_found")
    }
}
