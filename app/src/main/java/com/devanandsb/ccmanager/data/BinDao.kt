package com.devanandsb.ccmanager.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BinDao {

    /** Look up a single BIN prefix. Returns null when the BIN is not in the table. */
    @Query("SELECT * FROM bin_entries WHERE bin = :bin LIMIT 1")
    suspend fun find(bin: Int): BinEntry?

    /**
     * Bulk-insert BINs. Used once at startup to seed the table from the bundled CSV.
     * IGNORE keeps any rows you may have inserted or edited yourself.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<BinEntry>)

    /** Replace every row (use when you want to update the whole dataset). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<BinEntry>)

    /** True when at least one BIN has been seeded. */
    @Query("SELECT COUNT(*) FROM bin_entries")
    suspend fun count(): Int

    /** Remove every BIN (useful for a full data refresh). */
    @Query("DELETE FROM bin_entries")
    suspend fun clear()
}
