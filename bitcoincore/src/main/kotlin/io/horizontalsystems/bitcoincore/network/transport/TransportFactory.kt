package io.horizontalsystems.bitcoincore.network.transport

import io.horizontalsystems.bitcoincore.network.Network
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageParser
import io.horizontalsystems.bitcoincore.network.messages.NetworkMessageSerializer
import io.horizontalsystems.bitcoincore.network.transport.v2.crypto.SecureRandomEntropySource

/** Injection seam so tests can drive a connection with a scripted transport. */
internal interface ITransportFactory {
    fun create(useV2: Boolean): IPeerTransport
}

internal class DefaultTransportFactory(
    private val network: Network,
    private val parser: NetworkMessageParser,
    private val serializer: NetworkMessageSerializer,
) : ITransportFactory {

    override fun create(useV2: Boolean): IPeerTransport = if (useV2) {
        V2Transport(
            magicBytes = magicBytes(network.magic),
            usesDashShortIds = network.usesDashV2ShortIds,
            maxContentsLength = network.maxV2ContentsLength,
            parser = parser,
            serializer = serializer,
            entropy = SecureRandomEntropySource(),
        )
    } else {
        V1Transport(parser, serializer)
    }

    companion object {
        /**
         * The 4 message-start bytes in wire order, which is what BIP324 mixes into the HKDF salt.
         *
         * `Network.magic` is a Long that the v1 serializer writes little-endian, reproducing C++
         * `pchMessageStart` ordering — so the salt must use the same order. Getting this backwards
         * yields a stream neither side can decrypt, with no other symptom.
         */
        fun magicBytes(magic: Long) = byteArrayOf(
            (magic and 0xFF).toByte(),
            ((magic shr 8) and 0xFF).toByte(),
            ((magic shr 16) and 0xFF).toByte(),
            ((magic shr 24) and 0xFF).toByte(),
        )
    }
}
