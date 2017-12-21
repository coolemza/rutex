import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class SocketState() : LinkedHashMap<String, MutableMap<Book, MutableList<Depth>>>() {
    val pairCount = mutableMapOf<String, Long>()

    constructor(pair: String, depthLimit: Int = 0) : this() {
        pairCount.put(pair, 0)
        arrayOf(Book.asks, Book.bids).forEach { type ->
            for (i in 0 until depthLimit) {
                getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableListOf() }.add(Depth())
            }
        }
    }

    constructor(update: SocketState): this() {
        update.forEach { pair, p ->
            p.forEach { type ->
                type.value.forEach { d ->
                    getOrPut(pair) { mutableMapOf() }.getOrPut(type.key) { mutableListOf() }.add(Depth(d))
                }
            }
        }
    }
//
//    constructor(pairs: Map<String, PairInfo>, depthLimit: Int = 0) : this() {
//        pairs.forEach { val pair = it.key
//            pairCount.put(pair, 0)
//            arrayOf(Book.asks, Book.bids).forEach { type ->
//                for (i in 0 until depthLimit) {
//                    getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableListOf() }.add(Depth())
//                }
//            }
//        }
//    }

    fun replace(update: SocketState) {
        update.forEach { val pair = it.key
            it.value.forEach { val type = it.key
                it.value.forEachIndexed { index, depth ->
                    getOrPut(pair) { ConcurrentHashMap() }.getOrPut(type) { mutableListOf() }.run {
                        getOrNull(index)?.replace(depth) ?: add(index, Depth(depth))
                    }
                }
            }
        }
    }


    fun removeRate(pair: String, type: Book, rate: BigDecimal): String? {
        this[pair]?.get(type)?.let {
            if (it.size > 15) {
                it.indexOfFirst { it.rate == rate }.let { index ->
                    if (index == -1) {
                        this.remove(pair)
                        return "rate not found, pair: $pair, type: $type, rate: $rate"
                    } else {
                        it.remove(it[index])
                    }
                }
            } else {
                this.remove(pair)
                return "size < 15, pair: $pair, removed from state"
            }
        }
        return null
    }

    fun updateRate(pair: String, type: Book, rate: BigDecimal, amount: BigDecimal): String? {
        this[pair]?.get(type)?.let {
            if (it.size > 15) {
                var index = it.indexOfFirst { it.rate == rate }
                when (index) {
                    -1  -> {
                        index = when (type) {
                            Book.asks -> it.indexOfFirst { it.rate > rate }
                            Book.bids -> it.indexOfFirst { it.rate < rate }
                        }

                        when (index) {
                            -1   -> it.add(Depth(rate, amount))
                            else -> it.add(index, Depth(rate, amount))
                        }
                    }
                    else -> it.get(index).amount = amount
                }
            } else {
                this.remove(pair)
                return "pair: $pair, removed from state"
            }
        }
        return null
    }
}