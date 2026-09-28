package io.horizontalsystems.bitcoinkit.demo

import java.text.NumberFormat

object NumberFormatHelper {

    val fiatAmountFormat: NumberFormat
        get() {
            val numberFormat = NumberFormat.getInstance()
            numberFormat.maximumFractionDigits = 2
            numberFormat.minimumFractionDigits = 2
            return numberFormat
        }

    val cryptoAmountFormat: NumberFormat
        get() {
            val numberFormat = NumberFormat.getInstance()
            numberFormat.maximumFractionDigits = 12
            numberFormat.minimumFractionDigits = 2
            return numberFormat
        }

}

/** Satoshi rendered as whole coins, in the demo's 12-fraction-digit format. */
fun Long.formatSatoshi(): String = NumberFormatHelper.cryptoAmountFormat.format(this / 100_000_000.0)
