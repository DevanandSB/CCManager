package com.devanandsb.ccmanager.statementparser.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal

object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal =
        BigDecimal(decoder.decodeString())
}

@Serializable
enum class TransactionType {
    DEBIT,
    CREDIT,
}

@Serializable
data class StatementTransaction(
    val date: String, // ISO-8601 YYYY-MM-DD
    val description: String,
    @Serializable(with = BigDecimalSerializer::class)
    val amount: BigDecimal,
    val type: TransactionType,
    val page: Int = 1,
)
