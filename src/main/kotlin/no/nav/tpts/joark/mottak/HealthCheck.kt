package no.nav.tpts.joark.mottak

interface HealthCheck {
    val name: String
        get() = this.javaClass.simpleName

    fun status(): HealthStatus
}

enum class HealthStatus {
    UP, DOWN
}
