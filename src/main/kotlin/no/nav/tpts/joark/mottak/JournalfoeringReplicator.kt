package no.nav.tpts.joark.mottak

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.apache.avro.generic.GenericRecord
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties
import kotlin.coroutines.CoroutineContext

internal fun joarkConsumer(
    bootstrapServerUrl: String,
    username: String,
    password: String,
    schemaUrl: String,
    topicName: String
): KafkaConsumer<String, GenericRecord> {
    val maxPollRecords = 50
    val maxPollIntervalMs = Duration.ofSeconds(60 + maxPollRecords * 2.toLong()).toMillis()
    return KafkaConsumer<String, GenericRecord>(
        Properties().also {
            it["group.id"] = "foo"
            it["bootstrap.servers"] = bootstrapServerUrl
            it["username"] = username
            it["password"] = password
            it[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
            it[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
            it[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = KafkaAvroDeserializer::class.java
            it[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "false"
            it[AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG] = schemaUrl
            it[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = maxPollRecords
            it[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG] = "$maxPollIntervalMs"
        }
    ).also {
        it.subscribe(listOf(topicName))
    }
}

internal class JournalfoeringReplicator(
    private val consumer: Consumer<String, GenericRecord>
) : CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + Job()
}
