package data

import database.BookType
import kotlinx.serialization.Serializable
import stock.Update

@Serializable
class DepthBook() {
    val pairCount = mutableMapOf<String, Long>()
    val pairs = mutableMapOf<String, MutableMap<BookType, MutableList<Depth>>>()

    constructor(pair: String, depthLimit: Int = 0) : this() {
        pairCount.put(pair, 0)
        arrayOf(BookType.asks, BookType.bids).forEach { type ->
            for (i in 0 until depthLimit) {
                pairs.getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableListOf() }.add(Depth())
            }
        }
    }

    constructor(update: DepthBook): this() {
        update.pairs.forEach { pair, p ->
            p.forEach { type ->
                type.value.forEach { d ->
                    pairs.getOrPut(pair) { mutableMapOf() }.getOrPut(type.key) { mutableListOf() }.add(Depth(d))
                }
            }
        }
    }

    fun reset() {
        pairCount.clear()
        pairs.clear()
    }

//    constructor(pairs: Map<String, PairInfo>, depthLimit: Int = 0) : this() {
//        pairs.forEach { val pair = it.key
//            pairCount.put(pair, 0)
//            arrayOf(BookType.asks, BookType.bids).forEach { type ->
//                for (i in 0 until depthLimit) {
//                    pairs.getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableListOf() }.add(Depth())
//                }
//            }
//        }
//    }

    fun replace(update: DepthBook) {
        update.pairs.forEach { (pair, p) ->
            p.forEach { (type, t) ->
                t.forEachIndexed { i, depth ->
                    pairs.getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableListOf() }.run {
                        getOrNull(i)?.replace(depth) ?: add(i, Depth(depth))
                    }
                }
            }
        }
    }

    fun removeRate(upd: Update): Boolean {
        this.pairs[upd.pair]?.get(upd.type)?.let {
            it.indexOfFirst { it.rate == upd.rate }.let { index ->
                if (index == -1) {
                    return false
                } else {
                    return it.remove(it[index])
                }
            }
        }
        return false
    }

    fun updateRate(upd: Update): Boolean {
        this.pairs[upd.pair]?.get(upd.type)?.let {
            val index = it.indexOfFirst { it.rate == upd.rate }
            when (index) {
                -1 -> {
                    val indexToInsert = when (upd.type) {
                        BookType.asks -> it.indexOfFirst { it.rate > upd.rate }
                        BookType.bids -> it.indexOfFirst { it.rate < upd.rate }
                    }

                    when (indexToInsert) {
                        -1 -> it.add(Depth(upd.rate, upd.amount!!))
                        else -> it.add(indexToInsert, Depth(upd.rate, upd.amount!!))
                    }
                    return true
                }
                else -> {
                    it.get(index).amount = upd.amount!!
                    return true
                }
            }
        }
        return false
    }
}