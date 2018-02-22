package data

import database.OrderStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

data class Order(var id: Long, var summarize_id: Long, var order_id: Long, val type: String,
                 val pair: String, var rate: BigDecimal, var amount: BigDecimal, var remaining: BigDecimal,
                 var have: BigDecimal, var willGet: BigDecimal, val fee: BigDecimal,
                 var feeVal: BigDecimal, val stock: String, var status: OrderStatus, var  attempt: Int = 0) {

    constructor(stock: String, type: String, pair: String, rate: BigDecimal, amount: BigDecimal) : this(Math.abs(UUID.randomUUID().hashCode()).toLong(),
            0L, 0L, type, pair, rate, amount, amount, amount, amount, BigDecimal.ZERO, BigDecimal.ZERO, stock, OrderStatus.ACTIVE)

    fun getLockCur() = pair.split("_")[if (type == "buy") 1 else 0]

    fun getFromCur() = getLockCur()

    fun getToCur() = pair.split("_")[if (type == "buy") 0 else 1]

    fun getLockAmount(amount: BigDecimal = remaining) = (if (type == "buy") (rate * amount).setScale(8, RoundingMode.DOWN) else amount).stripTrailingZeros()

    fun getPlayedAmount(amount: BigDecimal = remaining) = ((if (type == "buy") (amount * fee).setScale(8, RoundingMode.DOWN) else
        (rate * amount * fee).setScale(8, RoundingMode.DOWN)).stripTrailingZeros())
}