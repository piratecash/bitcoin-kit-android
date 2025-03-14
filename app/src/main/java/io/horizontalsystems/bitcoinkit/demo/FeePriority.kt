package io.horizontalsystems.bitcoinkit.demo

sealed class FeePriority(val feeRate: Int) {
    object Low : FeePriority(5000)
    object Medium : FeePriority(7000)
    object High : FeePriority(10000)
}
