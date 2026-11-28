package com.devanandsb.ccmanager.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single BIN (first six digits of a card number) stored in the encrypted Room database.
 * Data is seeded once from [assets/bins_in.csv] the first time the app runs; from then on
 * you own the table and can extend / update it however you like.
 *
 * [bin]    – the six-digit BIN prefix, stored as a plain Int (no leading-zero ambiguity because
 *            Indian BINs always start with 1-9).
 * [issuer] – bank name, may be blank when unknown.
 * [kind]   – "credit" | "debit" | "prepaid" | "" when unknown.
 */
@Entity(tableName = "bin_entries")
data class BinEntry(
    @PrimaryKey val bin: Int,
    val issuer: String,
    val kind: String,
)
