package io.horizontalsystems.bitcoincore.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.bitcoincore.models.PeerAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class PeerAddressDaoTest {

    private lateinit var database: CoreDatabase
    private lateinit var peerAddressDao: PeerAddressDao

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CoreDatabase::class.java
        )
            .setTransactionExecutor(Executors.newSingleThreadExecutor())
            .setQueryExecutor(Executors.newSingleThreadExecutor())
            .allowMainThreadQueries()
            .build()

        peerAddressDao = database.peerAddress
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun getLeastScoreFastest_successfulPeerAndFreshPeer_prefersSuccessfulPeer() {
        peerAddressDao.insertAll(
            listOf(
                PeerAddress(ip = "fresh", score = 0, connectionTime = null),
                PeerAddress(ip = "successful", score = 1, connectionTime = 100)
            )
        )

        assertEquals("successful", peerAddressDao.getLeastScoreFastest(emptyList())?.ip)
        assertTrue(peerAddressDao.hasFresh(emptyList()))
    }

    @Test
    fun getLeastScoreFastest_successfulPeers_prefersHigherScore() {
        peerAddressDao.insertAll(
            listOf(
                PeerAddress(ip = "low-score", score = 1, connectionTime = 10),
                PeerAddress(ip = "high-score", score = 3, connectionTime = 100)
            )
        )

        assertEquals("high-score", peerAddressDao.getLeastScoreFastest(emptyList())?.ip)
    }

    @Test
    fun getLeastScoreFastest_sameScore_prefersFasterPeer() {
        peerAddressDao.insertAll(
            listOf(
                PeerAddress(ip = "slow", score = 1, connectionTime = 200),
                PeerAddress(ip = "fast", score = 1, connectionTime = 50)
            )
        )

        assertEquals("fast", peerAddressDao.getLeastScoreFastest(emptyList())?.ip)
    }

    @Test
    fun hasFresh_allFreshPeersExcluded_returnsFalse() {
        peerAddressDao.insertAll(
            listOf(
                PeerAddress(ip = "fresh", score = 0, connectionTime = null),
                PeerAddress(ip = "successful", score = 1, connectionTime = 100)
            )
        )

        assertFalse(peerAddressDao.hasFresh(listOf("fresh")))
    }
}
