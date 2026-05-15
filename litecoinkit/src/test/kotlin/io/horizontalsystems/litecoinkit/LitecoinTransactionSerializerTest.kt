package io.horizontalsystems.litecoinkit

import io.horizontalsystems.bitcoincore.extensions.hexToByteArray
import io.horizontalsystems.bitcoincore.extensions.toHexString
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.bitcoincore.io.BitcoinInputMarkable
import io.horizontalsystems.bitcoincore.utils.HashUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LitecoinTransactionSerializerTest {
    private val serializer = LitecoinTransactionSerializer()

    @Test
    fun deserialize_mwebPegInRaw_usesCanonicalPublicTxId() {
        val transaction = serializer.deserialize(BitcoinInputMarkable(MWEB_PEG_IN_RAW.hexToByteArray()))

        assertEquals(MWEB_PEG_IN_TX_ID, transaction.header.hash.toReversedHex())
        assertTrue(transaction.header.extraPayload.isNotEmpty())
    }

    @Test
    fun serializeDeserialize_mwebPegInRaw_preservesFullRawBytes() {
        val transaction = serializer.deserialize(BitcoinInputMarkable(MWEB_PEG_IN_RAW.hexToByteArray()))

        assertEquals(MWEB_PEG_IN_RAW, serializer.serialize(transaction).toHexString())
    }

    @Test
    fun serializeForTransactionHash_mwebPegInRaw_excludesWitnessAndMwebPayload() {
        val transaction = serializer.deserialize(BitcoinInputMarkable(MWEB_PEG_IN_RAW.hexToByteArray()))
        val hashBytes = serializer.serializeForTransactionHash(transaction)

        assertEquals("0200000001", hashBytes.take(5).toByteArray().toHexString())
        assertEquals(MWEB_PEG_IN_TX_ID, HashUtils.doubleSha256(hashBytes).toReversedHex())
    }

    @Test
    fun deserialize_publicP2wpkhRaw_usesSegwitTxIdWithoutWitness() {
        val transaction = serializer.deserialize(BitcoinInputMarkable(PUBLIC_P2WPKH_RAW.hexToByteArray()))
        val hashBytes = serializer.serializeForTransactionHash(transaction)

        assertEquals(PUBLIC_P2WPKH_TX_ID, transaction.header.hash.toReversedHex())
        assertEquals(PUBLIC_P2WPKH_TX_ID, HashUtils.doubleSha256(hashBytes).toReversedHex())
        assertEquals(PUBLIC_P2WPKH_RAW, serializer.serialize(transaction).toHexString())
        assertTrue(transaction.header.extraPayload.isEmpty())
    }

    private companion object {
        private const val PUBLIC_P2WPKH_TX_ID =
            "8530de8e771c830c4e76909b1fdf0e055ee8872fa1ebbf7c4279375591061a62"
        private const val PUBLIC_P2WPKH_RAW =
            "01000000000101dbf198515cebea6e248a212c63299e63a2a35a2def0a42e43e0106c2efff12860100000000ffffffff02e6988102000000001976a914d1b4380d709e9ea54943a083b1208d6d991893d988ac58271101000000001600149063d7cc1cf2d55f6c0076e65587c755dbe96ed702483045022100fa18145855d55b221c0df4cd72b12dcb26f451aa4b8ca2148ef535d3e374baff02205b13c14fbd8665be6a6da4fb65b46a737679e988956ec353a7c7e40cbe43f7a40121038d0705f4511adf850b16baf4f689d3d92fe63cc9a5f6d5d00d2e4ed699e511f800000000"
        private const val MWEB_PEG_IN_TX_ID =
            "742931afce282aee8f30d6aac7e72c07e1179c5a64d2ee0e14198c86f5e04075"
        private val MWEB_PEG_IN_RAW = listOf(
            "02000000000901568c122c0f066f3dd26a47af05378a9933eeba5b46c788319bfc5311d6bd94ac0100000000000000000276a6020000000000225920db7b23d24bcdb39416d3fd546891fe9058f2c998df87737616478d1530b8f974c3247f0000000000160014567e71b146cb3e518d8198ccdc81b802bec96a3602483045022100dd92007acc4dfd99879708b7a0c9e20bb42b9c5d6796e0238a53f8995bcfba27022006086106c863c2c8773030bce71027846d0171439454ffdcf3cc182323f6811d012103154531a4309a4c50c4a0f65a168be8c673ab8c313ce5ba82b834f8ce80881afb01fd0bdf378316868697e5d80d4027ccee1aa4039e24985cc39c20c727a4a5aa6a3f387a8798e2d0e4a5803cce7eceddd19f7d1aca794c8e4d05a89683be07edd20001087ac7f90d121eb95b4220c420af79f515d9e82a69c13dca917d793f345ed4b360039bedb190f79e6bf6407218b7105902fc83deeaf5352a747d7404d82ca5065c38038c94b56b6f7cd048c770780a533c509ab00d43795257512f365f0c13c5d1289d0103246e3e53b69ad7a5b7b4630b27828a120380626ada6d30510f9f6527fe2cc0566b226618f803400fd0a306139331bd6cb40490b5f9103150f7391228349048ef134ce5e5de0bac9431fe2c20d4fc87f88634a44abb77c38bfd51b425b54763b329df35ddd945513470cb8ce5c8039f37d9e6415bd9d0a7ff330c58888fc40aa8888225bb19f8cea692eee5d6288ee8bba3932470057ea1518fc2a1e4c1122977758a992b010ca3bfe976f05cfa947961723c3a0dc85fb2e4633dcdd9e9bfabc97727322b62f332196c65954a20429d4757ff09c872ce13c6d2ed86b04754814f7b116b3f21b251c0c5674f1c1b694521f2673b4f1f89a6cfc2a487947bf13bf0a5d7ec1cdc8f3ad25693b7205890562a1ff203b2dfb4e799f80230dda669749a00ad33b558c30d3d3a2415bee81c9e0e2ff31e8e439c5b5f59cc10db368a851bdbe26649f920f32b83da0eb964ea3295167e4f8afa6d2f901370e662e4c35dfdf7b72ab5577a1d2324afcd0be83635d3bea994d35aa79254be95df4b845bc8ca9cabc9a089d14815d40b8324349d726e43dbfed676fc1267b85675009ea3050f3379e8f47df8c26287edc0621ed1399ac9babf4bcc050c9a7e2144562776ac03f15a7ec2406ab4387020c1c0a5b8d9d416758381448b9a33cdd4cc5d6b28843487117dec9b7c3856423051912184a105521c863b41e2312f5d2be2b99b889653e3b94910a01da53c96036356ea6e4b9191399c233f350b0c2ad021fa38e43b0d5298b98fda75d0c5ea20e90f1772354262003386bfa3a2f1ffe9c929904f86a5ba7d40fc5d58a272e436cdd2032917c85310cdfe8c12369f2889278ee3bb61ed6da24f2900e4bec15699ae6e4d7d647f03bac31cae83f75e7de4903e77403d9cc5a943ba1a3270b9b11c5533726893ded3214b935fc64bac880dc000d93c301bbcaff19fa407a800caf61321a673965eb26fe19726fe1d37270c238cf413df47a7e5c3c9f2d43b0b07d741b0236d74b6f9e38cae2900523a5b5f81005e08e122b3f622166a9655e9d5e17011a52a33e556a74dcff964a8b0b6fc7e6dc8a1a1cd2344f7902cf5e23192723af2c1b91eb636fc9ed5ef535d303361c25201138f3489cb760288a41484b8fd68c9b9b9addac5795f329906ecfdad0e0d802efaa4a75f54f9da0928859a189ca429d9f98a51993138b410d7c41618c22ef562f1661b8e7b96a5bf8ab110261305a6bebd51d7d2007af2c6e865c69b7f65929642e20c12e81535bd91f3713f5410b0863efc2ffd253e4200699e4a6b44efcd26828030abcf931b6e00000000",
        ).joinToString("")
    }
}
