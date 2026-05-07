package io.horizontalsystems.litecoinkit.mweb

import io.horizontalsystems.litecoinkit.LitecoinKit
import org.junit.Assert.assertEquals
import org.junit.Test

class MwebRestorePolicyTest {
    private val policy = MwebRestorePolicy(MwebNetworkPolicy.network(LitecoinKit.NetworkType.MainNet))

    @Test
    fun resolve_activation_returnsMainnetActivationHeight() {
        assertEquals(2_257_920, policy.resolve(MwebRestorePoint.Activation))
    }

    @Test
    fun resolve_blockBeforeActivation_returnsActivationHeight() {
        assertEquals(2_257_920, policy.resolve(MwebRestorePoint.BlockHeight(1)))
    }

    @Test
    fun resolve_blockAfterActivation_returnsRequestedHeight() {
        assertEquals(2_300_000, policy.resolve(MwebRestorePoint.BlockHeight(2_300_000)))
    }
}
