package database

import kotlinx.serialization.Serializable
import stock.Bitfinex
import stock.Kraken
import stock.WEX

@Serializable
data class Key(val key: String, val secret: String, val type: KeyType)

@Serializable
data class RutKeys(val keys: Map<String, List<Key>>)

enum class C {
    ada,
    bab,
    bch,
    bsv,
    btc,
    btg,
    cad,
    cnh,
    doge,
    dsh,
    eos,
    etc,
    eth,
    eur,
    gbp,
    gno,
    iota,
    jpy,
    ltc,
    mln,
    nmc,
    nvc,
    omg,
    ppc,
    qtum,
    rep,
    rrt,
    rur,
    san,
    usd,
    usdt,
    xlm,
    xmr,
    xrp,
    xtz,
    zec
}

object RutData {
    fun getStocks(): Map<String, Int> {
        return mapOf(
            WEX.name to 1,
//                CEX.name to 2,
//                Poloniex.name to 3,
            Kraken.name to 4,
//                Huobi.name to 5,
            Bitfinex.name to 6
        )
    }

    fun getCurrencies() = C.values().map { it.name }

    fun isCrypto(cur: String) = cur != "usd" && cur != "eur" && cur != "rur" && cur != "jpy" && cur != "gbp"

    fun P(cur1: C, cur2: C) = "${cur1.name}_${cur2.name}"

    fun getStockPairs(): Map<String, List<String>> {
        return mapOf(
                "WEX" to WEX.Pairs,
                "Kraken" to Kraken.Pairs
        )
    }

    //Put your keys here or in Rutex.keys file
    // Rutex.keys is JSON file like:
    //{
    //keys:{
    //WEX:[
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:WALLET},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:TRADE},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:TRADE},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:TRADE},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:HISTORY},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:DEBUG},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:WITHDRAW},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:ACTIVE},
    //],
    //Kraken:[
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:WALLET},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:TRADE},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:TRADE},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:TRADE},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:HISTORY},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:DEBUG},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:WITHDRAW},
    //{key:PUT_KEY_HERE,secret:PUT_SECRET_HERE,type:ACTIVE},
    //]
    //}
    //}
    fun getTestKeys(): RutKeys {
        return RutKeys(mapOf(
                "WEX" to listOf(
//                        Key(key = "", secret = "", type = KeyType.WALLET),
//                        Key(key = "", secret = "", type = KeyType.TRADE),
//                        Key(key = "", secret = "", type = KeyType.TRADE),
//                        Key(key = "", secret = "", type = KeyType.TRADE),
//                        Key(key = "", secret = "", type = KeyType.HISTORY),
//                        Key(key = "", secret = "", type = KeyType.DEBUG),
//                        Key(key = "", secret = "", type = KeyType.WITHDRAW),
//                        Key(key = "", secret = "", type = KeyType.ACTIVE)
                ),
                "Kraken" to listOf(
//                        Key(key = "", secret = "", type = KeyType.WALLET),
//                        Key(key = "", secret = "", type = KeyType.TRADE),
//                        Key(key = "", secret = "", type = KeyType.TRADE),
//                        Key(key = "", secret = "", type = KeyType.TRADE),
//                        Key(key = "", secret = "", type = KeyType.HISTORY),
//                        Key(key = "", secret = "", type = KeyType.DEBUG),
//                        Key(key = "", secret = "", type = KeyType.WITHDRAW),
//                        Key(key = "", secret = "", type = KeyType.ACTIVE)                )
        )))
    }
}
