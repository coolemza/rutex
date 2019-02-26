package data

import database.BookType
import api.Update
import java.util.concurrent.atomic.AtomicInteger

class DepthList() : MutableList<Depth> by mutableListOf() {
    var nonce: Int = 0

    var updated = true
    var topUpdated = true

    var lockCounter = AtomicInteger()

    constructor(update: DepthList) : this() {
        nonce = update.nonce
        for (i in 0 until update.size) this.add(Depth(update[i]))
    }

    constructor(update: DepthList, depthLimit: Int) : this() {
        nonce = update.nonce
        val size = if (update.size < depthLimit) update.size else depthLimit
        for (i in 0 until size) this.add(Depth(update[i]))
    }

    fun deepReplace(update: DepthList) {
        if (update.nonce > nonce) {
            nonce = update.nonce
            updated = true

            if (update.size > 0) {
                if (size == 0 || update[0].rate != get(0).rate || update[0].amount != get(0).amount) {
                    topUpdated = true
                }
            }

            if (update.size == size) {
                update.forEachIndexed { i, depth -> get(i).replace(depth) }
            } else {
                clear()
                update.forEach { add(Depth(it)) }
            }
        } else {
            updated = false
            topUpdated = false
        }
    }

    fun replaceFromList(update: List<*>, depthLimit: Int) {
        val updateSize = if (update.size > depthLimit) depthLimit else update.size
        if (updateSize == size) {
            for (i in 0 until updateSize) {
                val data = update[i] as List<*>
                get(i).replace(data[0].toString(), data[1].toString())
            }
        } else {
            clear()
            for (i in 0 until updateSize) {
                val data = update[i] as List<*>
                add(Depth(data[0].toString(), data[1].toString()))
            }
        }
    }

    override fun toString(): String {
        return "$nonce($updated|$topUpdated)"
    }
}

class DepthType() : MutableMap<BookType, DepthList> by mutableMapOf() {

    constructor(update: DepthType) : this() {
        update.forEach { this[it.key] = DepthList(it.value) }
    }

    constructor(update: DepthType, depthLimit: Int) : this() {
        update.forEach { this[it.key] = DepthList(it.value, depthLimit) }
    }

    fun deepReplace(update: DepthType) {
        update.forEach { this.getOrPut(it.key) { DepthList() }.deepReplace(it.value) }
    }

    override fun toString(): String {
        return this.asSequence().joinToString { "${it.key.name}: ${it.value}" }
    }
}

class DepthBook() : MutableMap<String, DepthType> by mutableMapOf() {
    val pairCount = mutableMapOf<String, Long>()
    var nonce: ULong = 0u

    constructor(update: DepthBook) : this() {
        this.nonce = update.nonce
        update.forEach { this[it.key] = DepthType(it.value) }
    }

    constructor(update: DepthBook, depthLimit: Int) : this() {
        this.nonce = update.nonce
        update.forEach { this[it.key] = DepthType(it.value, depthLimit) }
    }

    fun resetPair(pair: String) {
        pairCount.remove(pair)
        this.get(pair)?.forEach { it.value.clear() }
    }

    fun reset() {
        pairCount.clear()
        forEach { it.value.forEach { it.value.clear() } }
    }

    fun incLock(pair: String, type: BookType) = this[pair]!![type]!!.lockCounter.incrementAndGet()

    fun decLock(pair: String, type: BookType) = this[pair]!![type]!!.lockCounter.decrementAndGet()

    fun isLocked(pair: String, type: BookType) = this[pair]?.get(type)?.run { lockCounter.get() > 0 } ?: false

    fun clearBook() = this.forEach { it.value.forEach { it.value.clear() } }

    fun deepReplace(update: DepthBook) {
        this.nonce = update.nonce
        update.forEach { this.getOrPut(it.key) { DepthType() }.deepReplace(it.value) }
    }

    fun replaceFromList(pair: String, type: String, data: List<*>, depthLimit: Int) {
        getOrPut(pair) { DepthType() }.getOrPut(BookType.valueOf(type)) { DepthList() }
            .replaceFromList(data, depthLimit)
//        val updateSize = if (data.size > depthLimit) depthLimit else data.size
//        val curList = getOrPut(pair) { DepthType() }.getOrPut(BookType.valueOf(type)) { DepthList() }
//
//        if (updateSize == size) {
//            for (i in 0 until updateSize) {
//                (data[i] as List<*>).let { curList.get(i).replace(it[0].toString(), it[1].toString()) }
//            }
//        } else {
//            curList.clear()
//            for (i in 0 until updateSize) {
//                (data[i] as List<*>).let { curList.add(Depth(it[0].toString(), it[1].toString())) }
//            }
//        }
    }

    fun removeRate(upd: Update): Int? = this[upd.pair]?.get(upd.type)?.let { depthList ->
        depthList.nonce ++
        depthList.indexOfFirst { it.rate == upd.rate }.let { index ->
            takeIf { index != -1 }?.run { depthList.remove(depthList[index]).takeIf { it }?.run { index } }
        }
    }

    fun updateRate(upd: Update): Int? = this[upd.pair]?.get(upd.type)?.let { depthList ->
        depthList.nonce ++
        when (val index = depthList.indexOfFirst { it.rate == upd.rate }) {
            -1 -> {
                val indexToInsert = when (upd.type) {
                    BookType.asks -> depthList.indexOfFirst { it.rate > upd.rate }
                    BookType.bids -> depthList.indexOfFirst { it.rate < upd.rate }
                }

                if (indexToInsert == -1) {
                    depthList.add(Depth(upd.rate, upd.amount!!))
                    return depthList.size
                } else {
                    depthList.add(indexToInsert, Depth(upd.rate, upd.amount!!))
                    return indexToInsert
                }
            }
            else -> {
                depthList.get(index).amount = upd.amount!!
                return index
            }
        }
    }


    override fun toString(): String {
        return "nonce: $nonce ${this.asSequence().joinToString { "${it.key}: ${it.value}" }}"
    }
}