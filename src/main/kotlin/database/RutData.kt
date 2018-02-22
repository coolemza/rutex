package database

import kotlinx.serialization.Serializable

@Serializable
data class Key(val key: String, val secret: String, val type: KeyType)

@Serializable
data class RutKeys(val keys: Map<String, List<Key>>)

object RutData {
    fun getStocks(): List<String> {
        return listOf(
                "WEX",
                "Kraken")
    }

    fun getCurrencies(): List<String> {
        return listOf(
                "btc",
                "ltc",
                "usd")
    }

    fun getPairs(): List<String> {
        return listOf(
                "btc_usd",
                "ltc_btc",
                "ltc_usd")
    }

    fun getTestKeys(): RutKeys {
        return RutKeys(mapOf(
                "WEX" to listOf(
                        Key("3HFBB6QZ-1SZDU4UL-U31EM2WB-YEOR1R2P-32GQN5X7", "c1df72d8acbbd91a09a8f9f13d477b7746b623b6cdaba7101d880963a38a7549", KeyType.WALLET),
                        Key("5AR7C4K3-VCLLC71H-QFDGR1ER-61O6I2N9-7UK88DTL", "bfcaac7e57a11441fd414c054acf80d786acd04e978a185e01b05617928cf472", KeyType.TRADE),
                        Key("FQRT4SYK-1L6KSTOT-MBTSQ4VY-M5OUUNQR-QPBD1CS5", "d748ebb8f93d3b15634633fd6cc11b43d5904173cde058985e64938e617ed8a8", KeyType.TRADE),
                        Key("R5VDWAQL-A7XH3MQM-LMCK8AUZ-5M0ET3TO-J93JVM20", "57b42858a0f424d32c530c7782d53c3a854363d601c7f2a00ff100b8b30ab375", KeyType.TRADE),
                        Key("EIJU3VBY-UJ6G0Y6C-G64GE1I1-FQTOE83I-RD72415T", "0d3dd50f0ae8fe7421bb44cf75f12287bb77f615e848c563a1b5c33a484cfc62", KeyType.HISTORY),
                        Key("HJ0NLZF3-AGHB7T0Z-HJ9ZCO83-KB1CJ8YZ-NZVTVMQ6", "0d601ed39dfae1fa52dfbb2b03de0198487f66ba6e0d7692b824d906f995624a", KeyType.DEBUG),
                        Key("VBXDPA22-NS5W3FAO-IFKAR8QC-SUT678LM-YT6UASLB", "1e44fab9661d2e7bf696e9eab841623195715ac8566924fad9054f17196afb60", KeyType.WITHDRAW)),
                "Kraken" to listOf(
                        Key("etxOoedEhaFv5/h06NnOxE2NDsTt38zwnphz4PNsRqx66ZbzpztsN/d8", "r9duXJcrBv5R5qBEWCOp2KfZexM7oti2Cn1Gh7NuHkDZPuqGr37oWLoGYi1xsevgW7UrES/CREpHKNhh0BIKoQ==", KeyType.WALLET),
                        Key("uUXwbHHv9PQbku6lRkqCz2kStUtv2D+cOtI3xICWbcNaJeR8vGcWXTsA", "ZYUTYOlcbtLft6YjCNUdIKqNRpkkbHjULzPCdUx3lkdce/mWsSVHDBySebF9OJ3PTp3oduA3i/oCZDYKAPRPaw==", KeyType.TRADE),
                        Key("4gQs737ykjZO6yumdFTJ7U8o06KC1BY+c7h9Vzf7TY8VsVjL4v8fRnG4", "y8IQ7qQuaTPYLdC6Sqk+ze/AYMM8/azOmu20ddmRLwX7HLpR4nDIZjUTs8012mp9+qMeygVSis/BVE742qTDBg==", KeyType.TRADE),
                        Key("JjHD5JhP8YUngdtUW1UMWzyr830XEPgM6PbhNDAJvnDRpE9nTImdaMnh", "F8o+O3/yw3Enhk7dQ98EsT7jo5XxZHAsWkEQiPffx0GbW/HFa+azwoUKBfaqdsg1NkmMkpHYdpG+TF7DqGvB8Q==", KeyType.TRADE),
                        Key("1SMnfGn1SXBQ+sG1mzr2RMqCwtPYKw1dCsrCObpJnmIMtg+giHfooxU+", "7S+TEPLitlec/n8WiO9bg6E8W6dYtDusqYE/auCX8OUS7ZKTR4sIu8qH8pPy2GUVJxPvilKabHk7EmjhvhIKMg==", KeyType.HISTORY),
                        Key("TCz60qd/QVJdzrLmcTyilYF2Ie1A9Vnc7S3jVIqSjKGjg/ysOUepIJUL", "bgcbMq0omkFl2XW68+WsdjE/e0M/ZdMsd3YIVIZ/l5YdqI61OfR6V/xw/1ZgWS2oPNIZ4C5IjJI62xU0x4/WGQ==", KeyType.DEBUG),
                        Key("vOsEA9jl5QKwtf8Y5e4VsNFP1N3P4Lb3iGI8b7mA1DtdVZoOEyNx5Ok3", "Dj01WSXG2nQjx3/k373dJkqZg42J09/XK9yzVJC0OLxzgugjGCG8NAH1qZF/K1W7lwlUH7ZdaJVnCbF7NS/8jw==", KeyType.WITHDRAW))
        ))
    }
}
