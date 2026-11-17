package io.horizontalsystems.bitcoinkit.demo

enum class FeePriority(val feeRate: Int) {
    Low(5000),
    Medium(7000),
    High(10000),
}
