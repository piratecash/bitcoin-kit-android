package io.horizontalsystems.bitcoincore.messages

import io.horizontalsystems.bitcoincore.core.IPrivateWallet
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.Wallet
import io.horizontalsystems.bitcoincore.core.scriptType
import io.horizontalsystems.bitcoincore.models.PublicKey
import io.horizontalsystems.bitcoincore.serializers.BaseTransactionSerializer
import io.horizontalsystems.bitcoincore.transactions.builder.EcdsaInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.IInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.ISchnorrInputSigner
import io.horizontalsystems.bitcoincore.transactions.builder.SchnorrInputSigner
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.HDWallet.Purpose
import io.horizontalsystems.hdwalletkit.Mnemonic
import kotlinx.coroutines.runBlocking
import org.bitcoinj.base.AddressParser
import org.bitcoinj.base.BitcoinNetwork
import org.bitcoinj.crypto.utils.MessageVerifyUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.Base64

class MessageSignerTest {

    private val addressConverter = mainNetAddressConverter()
    private val message = "  UTF-8 öäü 测试 😄\nsecond line "

    @Test
    fun sign_bip322OwnP2wpkhAddress_returnsUnprefixedWitnessThatVerifies() {
        val privateKey = privateKeyFromWif(P2WPKH_VECTOR_WIF)
        val publicKey = publicKeyOf(privateKey)
        val signer = softwareSigner(ScriptType.P2WPKH, SingleKeyWallet(privateKey), storageFor(publicKey, ScriptType.P2WPKH))

        val signature = sign(signer, P2WPKH_VECTOR_ADDRESS, MessageSignatureFormat.Bip322)

        assertFalse(signature.startsWith(BIP322_SIMPLE_PREFIX))
        assertTrue(verifyBip322(addressConverter.convert(P2WPKH_VECTOR_ADDRESS), message, witnessOf(signature)))
    }

    @Test
    fun sign_bip322OwnP2trAddress_returnsUnprefixedWitnessThatVerifies() {
        val privateKey = privateKeyFromWif(P2TR_VECTOR_WIF)
        val publicKey = publicKeyOf(privateKey)
        val signer = softwareSigner(ScriptType.P2TR, SingleKeyWallet(privateKey), storageFor(publicKey, ScriptType.P2TR))

        val signature = sign(signer, P2TR_VECTOR_ADDRESS, MessageSignatureFormat.Bip322)

        assertFalse(signature.startsWith(BIP322_SIMPLE_PREFIX))
        assertTrue(verifyBip322(addressConverter.convert(P2TR_VECTOR_ADDRESS), message, witnessOf(signature)))
    }

    @Test
    fun sign_standardPerScriptType_headerMatchesBip137AndBitcoinjVerifies() {
        val privateKey = privateKeyFromWif(P2WPKH_VECTOR_WIF)
        val publicKey = publicKeyOf(privateKey)
        val headers = mapOf(ScriptType.P2PKH to 31..34, ScriptType.P2WPKHSH to 35..38, ScriptType.P2WPKH to 39..42)

        headers.forEach { (scriptType, headerRange) ->
            val address = addressConverter.convert(publicKey, scriptType).stringValue
            val signer = softwareSigner(scriptType, SingleKeyWallet(privateKey), storageFor(publicKey, scriptType))

            val signature = sign(signer, address, MessageSignatureFormat.Standard)

            val decoded = Base64.getDecoder().decode(signature)
            assertEquals(65, decoded.size)
            assertTrue("$scriptType header ${decoded[0]}", decoded[0].toInt() in headerRange)
            verifyWithBitcoinj(address, signature)
        }
    }

    @Test
    fun sign_ownAddressOfEachPurpose_resolvesKeyAndWalletScriptType() {
        val seed = Mnemonic().toSeed(ABANDON_MNEMONIC.split(" "))
        val expectedAddresses = mapOf(
            Purpose.BIP44 to "1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA",
            Purpose.BIP49 to "37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf",
            Purpose.BIP84 to "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            Purpose.BIP86 to "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
        )

        expectedAddresses.forEach { (purpose, address) ->
            val wallet = Wallet(HDWallet(seed, 0, purpose), 20)
            val publicKey = wallet.publicKey(0, 0, true)
            val signer = softwareSigner(purpose.scriptType, wallet, storageFor(publicKey, purpose.scriptType))

            if (purpose == Purpose.BIP86) {
                val signature = sign(signer, address, MessageSignatureFormat.Bip322)
                assertTrue(verifyBip322(addressConverter.convert(address), message, witnessOf(signature)))
            } else {
                verifyWithBitcoinj(address, sign(signer, address, MessageSignatureFormat.Standard))
            }
        }
    }

    @Test
    fun sign_foreignOrInvalidAddress_throwsNotOwnAddress() {
        val publicKey = publicKeyOf(privateKeyFromWif(P2WPKH_VECTOR_WIF))
        val signer = softwareSigner(ScriptType.P2WPKH, SingleKeyWallet(privateKeyFromWif(P2WPKH_VECTOR_WIF)), storageFor(publicKey, ScriptType.P2WPKH))

        listOf(P2TR_VECTOR_ADDRESS, "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", "not an address").forEach { address ->
            assertThrows(MessageSignError.NotOwnAddress::class.java) { sign(signer, address, MessageSignatureFormat.Bip322) }
        }
    }

    @Test
    fun sign_ownKeyInAddressTypeOtherThanWallet_throwsNotOwnAddress() {
        val privateKey = privateKeyFromWif(P2WPKH_VECTOR_WIF)
        val publicKey = publicKeyOf(privateKey)
        val legacyAddress = addressConverter.convert(publicKey, ScriptType.P2PKH).stringValue
        val signer = softwareSigner(ScriptType.P2WPKH, SingleKeyWallet(privateKey), storageFor(publicKey, ScriptType.P2WPKH))

        assertThrows(MessageSignError.NotOwnAddress::class.java) {
            sign(signer, legacyAddress, MessageSignatureFormat.Standard)
        }
    }

    @Test
    fun sign_formatNotAcceptedForWalletType_throwsUnsupportedFormat() {
        val privateKey = privateKeyFromWif(P2WPKH_VECTOR_WIF)
        val publicKey = publicKeyOf(privateKey)
        val rejected = listOf(
            ScriptType.P2TR to MessageSignatureFormat.Standard,
            ScriptType.P2PKH to MessageSignatureFormat.Bip322,
            ScriptType.P2WPKHSH to MessageSignatureFormat.Bip322,
        )

        rejected.forEach { (scriptType, format) ->
            val address = addressConverter.convert(publicKey, scriptType).stringValue
            val signer = softwareSigner(scriptType, SingleKeyWallet(privateKey), storageFor(publicKey, scriptType))

            assertThrows("$scriptType $format", MessageSignError.UnsupportedFormat::class.java) { sign(signer, address, format) }
        }
    }

    @Test
    fun sign_withoutCapableSigner_throwsUnsupportedFormat() {
        val publicKey = publicKeyOf(privateKeyFromWif(P2WPKH_VECTOR_WIF))
        val storage = storageFor(publicKey, ScriptType.P2WPKH)
        val watchSigner = MessageSigner(null, null, storage, addressConverter, ScriptType.P2WPKH)
        val plainInputSigner = MessageSigner(mock<IInputSigner>(), mock<ISchnorrInputSigner>(), storage, addressConverter, ScriptType.P2WPKH)

        MessageSignatureFormat.values().forEach { format ->
            assertThrows(MessageSignError.UnsupportedFormat::class.java) { sign(watchSigner, P2WPKH_VECTOR_ADDRESS, format) }
        }
        assertThrows(MessageSignError.UnsupportedFormat::class.java) {
            sign(plainInputSigner, P2WPKH_VECTOR_ADDRESS, MessageSignatureFormat.Standard)
        }
    }

    private fun sign(signer: MessageSigner, address: String, format: MessageSignatureFormat) =
        runBlocking { signer.sign(address, message, format) }

    private fun softwareSigner(scriptType: ScriptType, wallet: IPrivateWallet, storage: IStorage): MessageSigner {
        val serializer = BaseTransactionSerializer()
        return MessageSigner(
            EcdsaInputSigner(wallet, serializer, bitcoinNetwork()),
            SchnorrInputSigner(wallet, serializer),
            storage,
            addressConverter,
            scriptType,
        )
    }

    // Answers only the lookup the wallet type is expected to use.
    private fun storageFor(publicKey: PublicKey, scriptType: ScriptType): IStorage = mock {
        when (scriptType) {
            ScriptType.P2WPKHSH -> on { getPublicKeyByScriptHashForP2PWKH(publicKey.scriptHashP2WPKH) } doReturn publicKey
            ScriptType.P2TR -> on { getPublicKeyByHashP2TR(publicKey.convertedForP2TR) } doReturn publicKey
            else -> {
                on { getPublicKeyByKeyOrKeyHash(publicKey.publicKeyHash) } doReturn publicKey
            }
        }
    }

    private fun verifyWithBitcoinj(address: String, signature: String) {
        MessageVerifyUtils.verifyMessage(AddressParser.getDefault(BitcoinNetwork.MAINNET).parseAddress(address), message, signature)
    }

    private companion object {
        const val P2WPKH_VECTOR_WIF = "L3VFeEujGtevx9w18HD1fhRbCH67Az2dpCymeRE1SoPK6XQtaN2k"
        const val P2WPKH_VECTOR_ADDRESS = "bc1q9vza2e8x573nczrlzms0wvx3gsqjx7vavgkx0l"
        const val P2TR_VECTOR_WIF = "KyrSGCFPhqZMjCe5fNTYddiLMp4tMj4gLKuJ26TsB2rvr1VJGPbt"
        const val P2TR_VECTOR_ADDRESS = "bc1pss0zhytly75awhm6x2hhvd5lnzv3vssgrf9axfheq8ldyzn88ges79fler"
        const val ABANDON_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }
}
