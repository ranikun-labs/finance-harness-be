package labs.ranikun.finance.identity.application

@JvmInline
value class IdentityUserId(val value: String) {

    init {
        require(value.isNotBlank()) { "Identity user id must not be blank" }
    }
}
