package data

import database.BookType
import database.OperationType
import database.OrderStatus
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

data class Order(var id: Long, var summarize_id: CompletableDeferred<Long>, var stockOrderId: String,
                 val type: OperationType, val book: BookType, val pair: String, var rate: BigDecimal,
                 var amount: BigDecimal, var remaining: BigDecimal, var have:BigDecimal, var willGet: BigDecimal,
                 var fee: BigDecimal, var crossFeeVal: BigDecimal, val stock: String, var status: OrderStatus, var  attempt: Int = 0) {
    var arb_ids = mutableMapOf<Int, Int>()

    constructor(o: Order): this(o.id, o.summarize_id, o.stockOrderId, o.type, o.book, o.pair, o.rate, o.amount, o.remaining,
        o.have, o.willGet, o.fee, o.crossFeeVal, o.stock, o.status, o.attempt)

    constructor(stock: String, type: OperationType, pair: String, rate: BigDecimal, amount: BigDecimal):
            this(Math.abs(UUID.randomUUID().hashCode()).toLong(),
                CompletableDeferred<Long>(), "0", type,
                if (type == OperationType.sell) BookType.bids else BookType.asks,
                pair, rate, amount, amount, amount, amount, BigDecimal.ZERO,
                BigDecimal.ZERO, stock, OrderStatus.ACTIVE)

    fun getLockCur() = pair.split("_")[if (type == OperationType.buy) 1 else 0]

    fun getFromCur() = getLockCur()

    fun getToCur() = pair.split("_")[if (type == OperationType.buy) 0 else 1]

    fun getLockAmount(amount: BigDecimal = remaining) = (if (type == OperationType.buy) (rate * amount).setScale(8, RoundingMode.DOWN) else amount).stripTrailingZeros()

    fun getPlayedAmount(amount: BigDecimal = remaining) = ((if (type == OperationType.buy) (amount * fee).setScale(8, RoundingMode.DOWN) else
        (rate * amount * fee).setScale(8, RoundingMode.DOWN)).stripTrailingZeros())

}