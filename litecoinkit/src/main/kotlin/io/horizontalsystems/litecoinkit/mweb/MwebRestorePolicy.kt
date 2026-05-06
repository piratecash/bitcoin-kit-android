package io.horizontalsystems.litecoinkit.mweb

class MwebRestorePolicy(
    private val network: MwebNetwork,
) {
    fun resolve(restorePoint: MwebRestorePoint): Int = when (restorePoint) {
        MwebRestorePoint.Activation -> network.activationHeight
        is MwebRestorePoint.BlockHeight -> maxOf(restorePoint.height, network.activationHeight)
    }
}
