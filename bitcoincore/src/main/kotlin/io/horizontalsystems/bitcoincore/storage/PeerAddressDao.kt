package io.horizontalsystems.bitcoincore.storage

import androidx.room.*
import io.horizontalsystems.bitcoincore.models.PeerAddress

@Dao
interface PeerAddressDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(peers: List<PeerAddress>)

    @Query(
        """
        SELECT * FROM PeerAddress
        WHERE ip NOT IN(:ips)
        ORDER BY
            CASE WHEN connectionTime IS NULL THEN 1 ELSE 0 END ASC,
            score DESC,
            connectionTime ASC
        LIMIT 1
        """
    )
    fun getLeastScoreFastest(ips: List<String>): PeerAddress?

    @Query("SELECT COUNT(*) > 0 FROM PeerAddress WHERE connectionTime IS NULL AND ip NOT IN(:ips)")
    fun hasFresh(ips: List<String>): Boolean

    @Query("UPDATE PeerAddress SET connectionTime = :time, score = score + 1 WHERE ip = :ip")
    fun setSuccessConnectionTime(time: Long, ip: String)

    @Delete
    fun delete(peerAddress: PeerAddress)
}
