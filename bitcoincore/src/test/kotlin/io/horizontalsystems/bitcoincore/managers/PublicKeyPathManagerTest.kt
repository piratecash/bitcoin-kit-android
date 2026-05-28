package io.horizontalsystems.bitcoincore.managers

import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import io.horizontalsystems.bitcoincore.core.IAccountWallet
import io.horizontalsystems.bitcoincore.core.IStorage
import io.horizontalsystems.bitcoincore.core.Wallet
import io.horizontalsystems.bitcoincore.models.PublicKey
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicKeyPathManagerTest {

    private val storage = mock<IStorage>()
    private val restoreKeyConverter = mock<RestoreKeyConverterChain>()
    private val accountWallet = mock<IAccountWallet>()
    private val wallet = mock<Wallet>()

    private val accountPublicKeyManager = AccountPublicKeyManager(storage, accountWallet, restoreKeyConverter)
    private val publicKeyManager = PublicKeyManager(storage, wallet, restoreKeyConverter)

    @Test
    fun getPublicKeyByPath_twoPartAccountPath_returnsExternalKey() {
        val publicKey = mock<PublicKey>()
        whenever(accountWallet.publicKey(5, true)).thenReturn(publicKey)

        val result = accountPublicKeyManager.getPublicKeyByPath("0/5")

        assertSame(publicKey, result)
        verify(accountWallet).publicKey(5, true)
    }

    @Test
    fun getPublicKeyByPath_twoPartInternalAccountPath_returnsInternalKey() {
        val publicKey = mock<PublicKey>()
        whenever(accountWallet.publicKey(5, false)).thenReturn(publicKey)

        val result = accountPublicKeyManager.getPublicKeyByPath("1/5")

        assertSame(publicKey, result)
        verify(accountWallet).publicKey(5, false)
    }

    @Test
    fun getPublicKeyByPath_threePartExternalAccountPath_returnsExternalKey() {
        val publicKey = mock<PublicKey>()
        whenever(accountWallet.publicKey(5, true)).thenReturn(publicKey)

        val result = accountPublicKeyManager.getPublicKeyByPath("0/0/5")

        assertSame(publicKey, result)
        verify(accountWallet).publicKey(5, true)
    }

    @Test
    fun getPublicKeyByPath_threePartInternalAccountPath_returnsInternalKey() {
        val publicKey = mock<PublicKey>()
        whenever(accountWallet.publicKey(5, false)).thenReturn(publicKey)

        val result = accountPublicKeyManager.getPublicKeyByPath("0/1/5")

        assertSame(publicKey, result)
        verify(accountWallet).publicKey(5, false)
    }

    @Test
    fun getPublicKeyByPath_nonZeroAccountAccountPath_throwsInvalidPath() {
        assertThrows(AccountPublicKeyManager.Error.InvalidPath::class.java) {
            accountPublicKeyManager.getPublicKeyByPath("1/0/5")
        }
    }

    @Test
    fun getPublicKeyByPath_invalidAccountPath_throwsInvalidPath() {
        listOf("", "0'/0/5", "m/0/5", "0/5/").forEach { path ->
            assertThrows(AccountPublicKeyManager.Error.InvalidPath::class.java) {
                accountPublicKeyManager.getPublicKeyByPath(path)
            }
        }
    }

    @Test
    fun getPublicKeyByPath_masterPath_returnsKey() {
        val publicKey = mock<PublicKey>()
        whenever(wallet.publicKey(0, 5, false)).thenReturn(publicKey)

        val result = publicKeyManager.getPublicKeyByPath("0/1/5")

        assertSame(publicKey, result)
        verify(wallet).publicKey(0, 5, false)
    }

    @Test
    fun getPublicKeyByPath_twoPartMasterPath_throwsInvalidPath() {
        assertThrows(PublicKeyManager.Error.InvalidPath::class.java) {
            publicKeyManager.getPublicKeyByPath("0/5")
        }
    }

    @Test
    fun getPublicKeyByPath_invalidMasterPath_throwsInvalidPath() {
        listOf("", "0'/0/5", "m/0/5", "0/5/").forEach { path ->
            assertThrows(PublicKeyManager.Error.InvalidPath::class.java) {
                publicKeyManager.getPublicKeyByPath(path)
            }
        }
    }
}
