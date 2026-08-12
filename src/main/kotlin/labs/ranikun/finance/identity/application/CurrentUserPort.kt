package labs.ranikun.finance.identity.application

interface CurrentUserPort {

    fun currentUserId(): IdentityUserId
}
