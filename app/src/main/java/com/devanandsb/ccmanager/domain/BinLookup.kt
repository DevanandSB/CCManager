package com.devanandsb.ccmanager.domain

import android.content.Context
import com.devanandsb.ccmanager.CardsApp
import com.devanandsb.ccmanager.data.BinEntry

/** What the first six digits say about a card. Either field may be unknown. */
data class BinInfo(val issuer: String?, val kind: String?)

/**
 * Offline BIN (first six digits) lookup for Indian cards.
 *
 * Data lives in the encrypted Room database (table `bin_entries`), giving you full control:
 * add, edit or bulk-replace rows through [com.devanandsb.ccmanager.data.BinDao] at any time.
 *
 * On the very first run the table is seeded automatically from [assets/bins_in.csv] by
 * [seed]; after that the asset is never read again. Nothing is sent anywhere.
 *
 * Coverage is partial: unknown prefixes return null.
 */
object BinLookup {

    /**
     * Look up [digits] (must be at least 6 characters) against the `bin_entries` table.
     * This is a suspend function; call it from a coroutine or [kotlinx.coroutines.withContext].
     */
    suspend fun find(context: Context, digits: String): BinInfo? {
        if (digits.length < 6) return null
        val bin = digits.take(6).toIntOrNull() ?: return null
        val entry = CardsApp.from(context).db.bins().find(bin) ?: return null
        return BinInfo(
            issuer = entry.issuer.takeIf { it.isNotBlank() },
            kind   = entry.kind.takeIf   { it.isNotBlank() },
        )
    }

    /**
     * Seed the `bin_entries` table from the bundled CSV asset the **first time only**
     * (i.e. when the table is empty). Safe to call repeatedly – it is a no-op once seeded.
     *
     * Call this once at app startup (see [com.devanandsb.ccmanager.CardsApp]).
     */
    suspend fun seed(context: Context) {
        val dao = CardsApp.from(context).db.bins()
        if (dao.count() > 0) return          // already seeded – nothing to do

        val entries = runCatching {
            context.assets.open("bins_in.csv").bufferedReader().useLines { lines ->
                lines
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val p = line.split(',')
                        val bin    = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                        val issuer = p.getOrNull(1)?.trim() ?: ""
                        val kind   = when (p.getOrNull(2)?.trim()) {
                            "c"  -> "credit"
                            "d"  -> "debit"
                            "p"  -> "prepaid"
                            else -> ""
                        }
                        BinEntry(bin, issuer, kind)
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())

        if (entries.isNotEmpty()) dao.insertAll(entries)
    }
}

