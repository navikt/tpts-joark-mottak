package no.nav.tpts.joark.mottak

import com.natpryce.konfig.ConfigurationProperties.Companion.systemProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.Key
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.StringDeserializer
import java.io.File
import java.time.Duration
import java.util.Properties
import kotlin.coroutines.CoroutineContext

private val LOGGER = KotlinLogging.logger {}

internal fun joarkConsumer(
    bootstrapServerUrl: String,
    schemaUrl: String,
    topicName: String
): KafkaConsumer<String, GenericRecord> {
    val maxPollRecords = 50
    val maxPollIntervalMs = Duration.ofSeconds(60 + maxPollRecords * 2.toLong()).toMillis()
    val config = systemProperties() overriding EnvironmentVariables
    val userName = config[Key("SRVTPTS_JOARK_MOTTAK_USERNAME", stringType)]
    val password = config[Key("SRVTPTS_JOARK_MOTTAK_PASSWORD", stringType)]
    LOGGER.info { "Found username: $userName" }
    return KafkaConsumer<String, GenericRecord>(
        Properties().also {
            it[ConsumerConfig.GROUP_ID_CONFIG] = "tpts-journalfoering-logger-v1"
            it[CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG] = bootstrapServerUrl
            it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
            it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
            it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = KafkaAvroDeserializer::class.java
            it[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "false"
            it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = schemaUrl
            it[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = maxPollRecords
            it[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG] = "$maxPollIntervalMs"
            LOGGER.info { "authenticating against Kafka brokers " }
            it[SaslConfigs.SASL_MECHANISM] = "PLAIN"
            it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "SASL_PLAINTEXT"
            it[SaslConfigs.SASL_JAAS_CONFIG] =
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$userName\" password=\"$password\";"
            val trustStoreLocation = System.getenv("NAV_TRUSTSTORE_PATH")
            trustStoreLocation?.apply {
                try {
                    it[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "SASL_SSL"
                    it[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] = File(this).absolutePath
                    it[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = System.getenv("NAV_TRUSTSTORE_PASSWORD")
                    LOGGER.info { "Configured '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location " }
                } catch (e: Exception) {
                    LOGGER.error { "Failed to set '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location " }
                }
            }
        }
    ).also {
        it.subscribe(listOf(topicName))
    }
}

internal class JournalfoeringReplicator(
    private val consumer: Consumer<String, GenericRecord>
) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job
    private val job: Job = Job()

    init {
        Runtime.getRuntime().addShutdownHook(Thread(::shutdownHook))
    }

    fun start() {
        LOGGER.info { "starting JournalfoeringReplicator" }
        launch {
            run()
        }
    }

    private fun stop() {
        LOGGER.info { "stopping JournalfoeringReplicator" }
        consumer.wakeup()
        job.cancel()
    }

    private fun run() {
        try {
            while (job.isActive) {
                onRecords(consumer.poll(Duration.ofSeconds(1)))
            }
        } catch (e: WakeupException) {
            if (job.isActive) throw e
        } catch (e: Exception) {
            LOGGER.error(e) { "Noe feil skjedde i konsumeringen" }
            throw e
        } finally {
            closeResources()
        }
    }

    private fun onRecords(records: ConsumerRecords<String, GenericRecord>) {
        LOGGER.debug { "onrecords: ${records.count()}" }
        if (records.isEmpty) return // poll returns an empty collection in case of rebalancing
        val currentPositions = records
            .groupBy { TopicPartition(it.topic(), it.partition()) }
            .mapValues { partition -> partition.value.minOf { it.offset() } }
            .toMutableMap()
        try {
            records.onEach { record ->
                val tema = record.value().get("temaNytt")?.toString() ?: ""
                LOGGER.info { "$currentPositions: Mottok tema '$tema'. " + if (tema == "IND" || tema == "TIL") "$record" else "Hopp over" }
                currentPositions[TopicPartition(record.topic(), record.partition())] = record.offset() + 1
            }
        } catch (err: Exception) {
            LOGGER.info(
                "due to an error during processing, positions are reset to each next message (after each record that was processed OK):" +
                    currentPositions.map { "\tpartition=${it.key}, offset=${it.value}" }
                        .joinToString(separator = "\n", prefix = "\n", postfix = "\n"),
                err
            )
            currentPositions.forEach { (partition, offset) -> consumer.seek(partition, offset) }
            throw err
        } finally {
            consumer.commitSync()
        }
    }

    private fun closeResources() {
        LOGGER.info { "Closing resources" }
        tryAndLog(consumer::unsubscribe)
        tryAndLog(consumer::close)
    }

    private fun tryAndLog(block: () -> Unit) {
        try {
            block()
        } catch (err: Exception) {
            LOGGER.error(err) { err.message }
        }
    }

    private fun shutdownHook() {
        LOGGER.info { "received shutdown signal, stopping app" }
        stop()
    }
}
