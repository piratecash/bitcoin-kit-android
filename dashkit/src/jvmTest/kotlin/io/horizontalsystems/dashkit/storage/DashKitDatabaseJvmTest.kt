package io.horizontalsystems.dashkit.storage

import io.horizontalsystems.dashkit.models.Masternode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DashKitDatabaseJvmTest {

    @Test
    fun getInstance_dataDir_createsDatabaseFileAndServesDao() {
        val dataDir = Files.createTempDirectory("dashkit-jvm").toFile()
        val database = DashKitDatabase.getInstance(dataDir.path, "dash.db")
        try {
            database.masternodeDao.insertAll(listOf(masternode(byteArrayOf(1, 2, 3))))

            assertTrue(File(dataDir, "dash.db").exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), database.masternodeDao.getAll().single().proRegTxHash)
        } finally {
            database.close()
            dataDir.deleteRecursively()
        }
    }

    private fun masternode(proRegTxHash: ByteArray) = Masternode().apply {
        this.proRegTxHash = proRegTxHash
        hash = proRegTxHash
    }
}
