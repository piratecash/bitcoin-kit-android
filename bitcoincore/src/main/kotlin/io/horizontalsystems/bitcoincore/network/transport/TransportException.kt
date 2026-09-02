package io.horizontalsystems.bitcoincore.network.transport

/**
 * Fatal transport-level failures (plan §2.5).
 *
 * A v2 stream cannot resynchronize: once the length or packet cipher has consumed state, every
 * later byte is garbage. So unlike the v1 path — which deliberately keeps its swallow-and-continue
 * behaviour — anything in this family must terminate the connection.
 *
 * Only [V2Transport] raises these. `V1Transport` keeps throwing the legacy `BitcoinException`
 * so v1 behaviour stays byte-for-byte unchanged for every kit.
 */
internal sealed class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Any failure that happened while the BIP324 handshake was still running — protocol violation,
     * EOF, deadline, garbage cap, or a bad AEAD tag alike.
     *
     * The blanket classification is deliberate (plan §2.2.2): `PeerGroup` falls back to v1 only on
     * this type, and the single most common real case is a legacy peer that simply closes the
     * socket. Reporting that as anything else routes the address to `markFailed`, which deletes it
     * from storage — and PirateCash has three DNS seeds in total.
     */
    class HandshakeFailed(message: String, cause: Throwable? = null) : TransportException(message, cause)

    /** A packet decrypted correctly but its framing is unusable (bad length, malformed contents). */
    class MalformedMessage(message: String) : TransportException(message)

    /** AEAD tag mismatch on an established stream: the peer is not who we negotiated with. */
    class AuthenticationFailed(message: String) : TransportException(message)

    /** The connection was closed locally; never a reason to downgrade a peer to v1. */
    class StreamClosed(message: String) : TransportException(message)
}

/**
 * A fully received, authenticated packet whose payload parser then failed.
 *
 * Recoverable on purpose: the cipher state is intact, so the stream survives and only this one
 * message is dropped. Kept distinct from [TransportException] for exactly that reason.
 */
internal class MessagePayloadException(message: String, cause: Throwable? = null) : Exception(message, cause)
