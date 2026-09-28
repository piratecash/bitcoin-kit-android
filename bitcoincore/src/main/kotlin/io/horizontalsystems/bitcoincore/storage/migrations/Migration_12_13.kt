package io.horizontalsystems.bitcoincore.storage.migrations

import androidx.room.migration.Migration
import androidx.room.util.getColumnIndexOrThrow
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.horizontalsystems.bitcoincore.models.ScriptTypeConverter
import io.horizontalsystems.bitcoincore.models.Transaction
import io.horizontalsystems.bitcoincore.models.TransactionInput
import io.horizontalsystems.bitcoincore.models.TransactionMetadata
import io.horizontalsystems.bitcoincore.models.TransactionOutput
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.storage.FullTransaction
import io.horizontalsystems.bitcoincore.storage.WitnessConverter
import io.horizontalsystems.bitcoincore.transactions.extractors.ITransactionOutputProvider
import io.horizontalsystems.bitcoincore.transactions.extractors.MyOutputsCache
import io.horizontalsystems.bitcoincore.transactions.extractors.TransactionMetadataExtractor

object Migration_12_13 : Migration(12, 13) {

    private val __witnessConverter = WitnessConverter()
    private val __scriptTypeConverter = ScriptTypeConverter()

    override fun migrate(connection: SQLiteConnection) {
        createTableTransactionMetadata(connection)
        createMetadataForExistingTransactions(connection)
        deleteInvalidTransactions(connection)
    }

    private fun deleteInvalidTransactions(connection: SQLiteConnection) {
        connection.execSQL("DELETE FROM `InvalidTransaction`")
    }

    private fun createTableTransactionMetadata(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `TransactionMetadata` (`amount` INTEGER NOT NULL, `type` INTEGER NOT NULL, `fee` INTEGER, `transactionHash` BLOB NOT NULL, PRIMARY KEY(`transactionHash`))")
    }

    private fun createMetadataForExistingTransactions(connection: SQLiteConnection) {
        val transactions = getTransactions(connection)
        val inputs = getTransactionInputs(connection)
        val outputs = getTransactionOutputs(connection)

        val myOutputsCache = MyOutputsCache().apply {
            add(outputs)
        }

        val outputProvider = object : ITransactionOutputProvider {
            override fun get(transactionHash: ByteArray, index: Int): TransactionOutput? {
                return outputs.find {
                    it.transactionHash.contentEquals(transactionHash) && it.index == index
                }
            }
        }

        val metadataExtractor = TransactionMetadataExtractor(myOutputsCache, outputProvider)
        transactions.forEach { transaction ->
            val transactionInputs = inputs.filter {
                it.transactionHash.contentEquals(transaction.hash)
            }

            val transactionOutputs = outputs.filter {
                it.transactionHash.contentEquals(transaction.hash)
            }

            val fullTransaction =
                FullTransaction(
                    transaction, transactionInputs, transactionOutputs,
                    BaseTransactionSerializer()
                )
            metadataExtractor.extract(fullTransaction)

            insertTransactionMetadata(connection, fullTransaction.metadata)
        }
    }

    private fun insertTransactionMetadata(
        connection: SQLiteConnection,
        metadata: TransactionMetadata
    ) {
        connection.prepare(
            "INSERT OR REPLACE INTO `TransactionMetadata` (transactionHash,amount,type,fee) VALUES(?, ?, ?, ?)"
        ).use { st ->
            st.bindBlob(1, metadata.transactionHash)
            st.bindLong(2, metadata.amount)
            st.bindLong(3, metadata.type.value.toLong())
            val fee = metadata.fee
            if (fee == null) {
                st.bindNull(4)
            } else {
                st.bindLong(4, fee)
            }
            st.step()
        }
    }

    private fun getTransactionOutputs(connection: SQLiteConnection): List<TransactionOutput> {
        connection.prepare("SELECT * FROM TransactionOutput ORDER BY rowId").use { st ->
            val _cursorIndexOfValue = getColumnIndexOrThrow(st, "value")
            val _cursorIndexOfLockingScript = getColumnIndexOrThrow(st, "lockingScript")
            val _cursorIndexOfRedeemScript = getColumnIndexOrThrow(st, "redeemScript")
            val _cursorIndexOfIndex = getColumnIndexOrThrow(st, "index")
            val _cursorIndexOfTransactionHash = getColumnIndexOrThrow(st, "transactionHash")
            val _cursorIndexOfPublicKeyPath = getColumnIndexOrThrow(st, "publicKeyPath")
            val _cursorIndexOfChangeOutput = getColumnIndexOrThrow(st, "changeOutput")
            val _cursorIndexOfScriptType = getColumnIndexOrThrow(st, "scriptType")
            val _cursorIndexOfKeyHash = getColumnIndexOrThrow(st, "keyHash")
            val _cursorIndexOfAddress = getColumnIndexOrThrow(st, "address")
            val _cursorIndexOfFailedToSpend = getColumnIndexOrThrow(st, "failedToSpend")
            val _cursorIndexOfPluginId = getColumnIndexOrThrow(st, "pluginId")
            val _cursorIndexOfPluginData = getColumnIndexOrThrow(st, "pluginData")
            val _result = mutableListOf<TransactionOutput>()
            while (st.step()) {
                val _item = TransactionOutput()
                _item.value = st.getLong(_cursorIndexOfValue)
                _item.lockingScript = st.getBlob(_cursorIndexOfLockingScript)
                _item.redeemScript = if (st.isNull(_cursorIndexOfRedeemScript)) null else st.getBlob(_cursorIndexOfRedeemScript)
                _item.index = st.getInt(_cursorIndexOfIndex)
                _item.transactionHash = st.getBlob(_cursorIndexOfTransactionHash)
                _item.publicKeyPath = if (st.isNull(_cursorIndexOfPublicKeyPath)) null else st.getText(_cursorIndexOfPublicKeyPath)
                _item.changeOutput = st.getBoolean(_cursorIndexOfChangeOutput)
                val _tmp_1 = if (st.isNull(_cursorIndexOfScriptType)) {
                    null
                } else {
                    st.getInt(_cursorIndexOfScriptType)
                }
                __scriptTypeConverter.fromInt(_tmp_1)?.let {
                    _item.scriptType = it
                }
                _item.lockingScriptPayload = if (st.isNull(_cursorIndexOfKeyHash)) null else st.getBlob(_cursorIndexOfKeyHash)
                _item.address = if (st.isNull(_cursorIndexOfAddress)) null else st.getText(_cursorIndexOfAddress)
                _item.failedToSpend = st.getBoolean(_cursorIndexOfFailedToSpend)
                _item.pluginId = if (st.isNull(_cursorIndexOfPluginId)) null else st.getLong(_cursorIndexOfPluginId).toByte()
                _item.pluginData = if (st.isNull(_cursorIndexOfPluginData)) null else st.getText(_cursorIndexOfPluginData)
                _result.add(_item)
            }
            return _result
        }
    }

    private fun getTransactionInputs(connection: SQLiteConnection): List<TransactionInput> {
        connection.prepare("SELECT * FROM TransactionInput").use { st ->
            val _cursorIndexOfTransactionHash = getColumnIndexOrThrow(st, "transactionHash")
            val _cursorIndexOfKeyHash = getColumnIndexOrThrow(st, "keyHash")
            val _cursorIndexOfAddress = getColumnIndexOrThrow(st, "address")
            val _cursorIndexOfWitness = getColumnIndexOrThrow(st, "witness")
            val _cursorIndexOfPreviousOutputTxHash = getColumnIndexOrThrow(st, "previousOutputTxHash")
            val _cursorIndexOfPreviousOutputIndex = getColumnIndexOrThrow(st, "previousOutputIndex")
            val _cursorIndexOfSigScript = getColumnIndexOrThrow(st, "sigScript")
            val _cursorIndexOfSequence = getColumnIndexOrThrow(st, "sequence")
            val _result = mutableListOf<TransactionInput>()
            while (st.step()) {
                val _item = TransactionInput(
                    st.getBlob(_cursorIndexOfPreviousOutputTxHash),
                    st.getLong(_cursorIndexOfPreviousOutputIndex),
                    st.getBlob(_cursorIndexOfSigScript),
                    st.getLong(_cursorIndexOfSequence)
                )
                _item.transactionHash = st.getBlob(_cursorIndexOfTransactionHash)
                _item.lockingScriptPayload = if (st.isNull(_cursorIndexOfKeyHash)) null else st.getBlob(_cursorIndexOfKeyHash)
                _item.address = if (st.isNull(_cursorIndexOfAddress)) null else st.getText(_cursorIndexOfAddress)
                _item.witness = __witnessConverter.toWitness(st.getText(_cursorIndexOfWitness))
                _result.add(_item)
            }
            return _result
        }
    }

    private fun getTransactions(connection: SQLiteConnection): List<Transaction> {
        connection.prepare("SELECT * FROM `Transaction`").use { st ->
            val _cursorIndexOfUid = getColumnIndexOrThrow(st, "uid")
            val _cursorIndexOfHash = getColumnIndexOrThrow(st, "hash")
            val _cursorIndexOfBlockHash = getColumnIndexOrThrow(st, "blockHash")
            val _cursorIndexOfVersion = getColumnIndexOrThrow(st, "version")
            val _cursorIndexOfLockTime = getColumnIndexOrThrow(st, "lockTime")
            val _cursorIndexOfTimestamp = getColumnIndexOrThrow(st, "timestamp")
            val _cursorIndexOfOrder = getColumnIndexOrThrow(st, "order")
            val _cursorIndexOfIsMine = getColumnIndexOrThrow(st, "isMine")
            val _cursorIndexOfIsOutgoing = getColumnIndexOrThrow(st, "isOutgoing")
            val _cursorIndexOfSegwit = getColumnIndexOrThrow(st, "segwit")
            val _cursorIndexOfStatus = getColumnIndexOrThrow(st, "status")
            val _cursorIndexOfSerializedTxInfo = getColumnIndexOrThrow(st, "serializedTxInfo")
            val _cursorIndexOfConflictingTxHash = getColumnIndexOrThrow(st, "conflictingTxHash")
            val _cursorIndexOfRawTransaction = getColumnIndexOrThrow(st, "rawTransaction")
            val _result = mutableListOf<Transaction>()
            while (st.step()) {
                val _item = Transaction()
                _item.uid = st.getText(_cursorIndexOfUid)
                _item.hash = st.getBlob(_cursorIndexOfHash)
                _item.blockHash = if (st.isNull(_cursorIndexOfBlockHash)) null else st.getBlob(_cursorIndexOfBlockHash)
                _item.version = st.getInt(_cursorIndexOfVersion)
                _item.lockTime = st.getLong(_cursorIndexOfLockTime)
                _item.timestamp = st.getLong(_cursorIndexOfTimestamp)
                _item.order = st.getInt(_cursorIndexOfOrder)
                _item.isMine = st.getBoolean(_cursorIndexOfIsMine)
                _item.isOutgoing = st.getBoolean(_cursorIndexOfIsOutgoing)
                _item.segwit = st.getBoolean(_cursorIndexOfSegwit)
                _item.status = st.getInt(_cursorIndexOfStatus)
                _item.serializedTxInfo = st.getText(_cursorIndexOfSerializedTxInfo)
                _item.conflictingTxHash = if (st.isNull(_cursorIndexOfConflictingTxHash)) null else st.getBlob(_cursorIndexOfConflictingTxHash)
                _item.rawTransaction = if (st.isNull(_cursorIndexOfRawTransaction)) null else st.getText(_cursorIndexOfRawTransaction)
                _result.add(_item)
            }
            return _result
        }
    }
}
