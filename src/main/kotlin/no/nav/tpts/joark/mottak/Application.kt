package no.nav.tpts.joark.mottak

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.DefaultHeaders
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respondText
import io.ktor.response.respondTextWriter
import io.ktor.routing.Route
import io.ktor.routing.get
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import mu.KotlinLogging

private val LOGGER = KotlinLogging.logger {}

fun main() {
    val replicator = JournalfoeringReplicator(
        joarkConsumer(
            bootstrapServerUrl = "b27apvl00045.preprod.local:8443,b27apvl00046.preprod.local:8443,b27apvl00047.preprod.local:8443",
            schemaUrl = "https://kafka-schema-registry.nais-q.adeo.no",
            topicName = "aapen-dok-journalfoering-v1-q1"
        )
    ).also { it.start() }

    LOGGER.info { "starting server" }
    val server = embeddedServer(Netty, 8080) {
        install(DefaultHeaders)
        routing {
            healthRoutes(listOf(replicator))
        }
    }.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            LOGGER.info { "stopping server" }
            server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
        }
    )
}

fun Route.healthRoutes(healthChecks: List<HealthCheck>) {
    route("/metrics") {
        get {
            call.respondTextWriter {
                TextFormat.writeFormat(
                    TextFormat.CONTENT_TYPE_004,
                    this,
                    CollectorRegistry.defaultRegistry.metricFamilySamples()
                )
            }
        }
    }.also { LOGGER.info { "setting up endpoint /metrics" } }
    route("/isAlive") {
        get {
            val failedHealthChecks = healthChecks.filter { it.status() == HealthStatus.DOWN }
            if (failedHealthChecks.isNotEmpty()) {
                LOGGER.warn { "Failed health checks: $failedHealthChecks" }
                call.respondText(text = "ERROR", contentType = ContentType.Text.Plain, HttpStatusCode.ServiceUnavailable)
            } else {
                call.respondText(text = "ALIVE", contentType = ContentType.Text.Plain)
            }
        }
    }.also { LOGGER.info { "setting up endpoint /isAlive" } }
    route("/isReady") {
        get {
            call.respondText(text = "READY", contentType = ContentType.Text.Plain)
        }
    }.also { LOGGER.info { "setting up endpoint /isReady" } }
}
