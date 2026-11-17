package io.horizontalsystems.bitcoincore.messages

sealed class MessageSignError : Exception() {
    class NotOwnAddress : MessageSignError()
    class UnsupportedFormat : MessageSignError()
    class SignatureMismatch : MessageSignError()
}
