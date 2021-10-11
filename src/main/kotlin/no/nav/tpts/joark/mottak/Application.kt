package no.nav.tpts.joark.mottak

import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.DefaultHeaders
import io.ktor.http.ContentType
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
    val journalfoeringReplicator = JournalfoeringReplicator(
        joarkConsumer(
            bootstrapServerUrl = "b27apvl00045.preprod.local:8443,b27apvl00046.preprod.local:8443,b27apvl00047.preprod.local:8443",
            username = "",
            password = "",
            schemaUrl = "https://kafka-schema-registry.nais-q.adeo.no",
            topicName = "aapen-dok-journalfoering-v1-q1"
        )
    ).also { it.start() }

    LOGGER.info { "starting server" }
    val server = embeddedServer(Netty, 8080) {
        install(DefaultHeaders)
        routing {
            healthRoutes()
        }
    }.start()

    Runtime.getRuntime().addShutdownHook(
        Thread {
            LOGGER.info { "stopping server" }
            server.stop(gracePeriodMillis = 3000, timeoutMillis = 5000)
        }
    )
}

fun Route.healthRoutes() {
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
            call.respondText(text = "ALIVE", contentType = ContentType.Text.Plain)
        }
    }.also { LOGGER.info { "setting up endpoint /isAlive" } }
    route("/isReady") {
        get {
            call.respondText(text = "READY", contentType = ContentType.Text.Plain)
        }
    }.also { LOGGER.info { "setting up endpoint /isReady" } }
}
