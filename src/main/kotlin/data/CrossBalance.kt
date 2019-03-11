package data

import database.PlayType
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class Balance(var limit: BigDecimal, private var progress: BigDecimal, var stop: Boolean) {
    lateinit var feeVal: BigDecimal
    private var locked = BigDecimal.ZERO
    private var available = AtomicReference<BigDecimal>(limit - progress - locked)

    constructor(balance: Balance) : this(balance.limit, balance.progress, balance.stop) {
        takeIf { balance::feeVal.isInitialized }?.run { feeVal = balance.feeVal }  //FIXME: optimze it for limitLess
    }

    fun updateAvailable() = available.set(limit - progress - locked)

    fun lock(amount: BigDecimal) {
        locked += amount
        updateAvailable()
    }

    fun unLockPlayed(amount: BigDecimal) {
        progress += amount
        locked -= amount
        updateAvailable()
    }

    fun unLockCanceled(amount: BigDecimal) {
        locked -= amount
        updateAvailable()
    }

    fun progressIncrease(amount: BigDecimal) {
        progress += amount
        updateAvailable()
    }

    fun progressDecrease(amount: BigDecimal) {
        progress -= amount
        updateAvailable()
    }

    fun getProgress() = progress
    fun getAvailable() = available.get()

    override fun toString() = "${progress.toPlainString()} + ${locked.toPlainString()}(${limit.toPlainString()})"
}

class CrossBalance() : HashMap<String, MutableMap<String, MutableMap<PlayType, Balance>>>() {
    constructor(balance: CrossBalance): this() {
        balance.forEach { stockTo, st ->
            st.forEach { cur, c ->
                c.forEach { type, bal ->
                    getOrPut(stockTo) { mutableMapOf() }.getOrPut(cur) { mutableMapOf() }.getOrPut(type) { Balance(bal) }
                }
            }
        }
    }
}
