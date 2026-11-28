package com.devanandsb.ccmanager.statementparser.domain.model

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class StatementSummary(
    @Serializable(with = BigDecimalSerializer::class)
    val totalAmountDue: BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val minimumAmountDue: BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val creditLimit: BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val availableCredit: BigDecimal? = null,
    val statementDate: String? = null,
    val paymentDueDate: String? = null,
    val statementPeriod: String? = null,
    val cardLast4Digits: String? = null,
    val bankName: String? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val openingBalance: BigDecimal? = null,
    @Serializable(with = BigDecimalSerializer::class)
    val currentPurchases: BigDecimal? = null,
)
