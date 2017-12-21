import db.OrderStatus
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import java.util.concurrent.Future

data class Order(var id: Long, var summarize_id: Long, var order_id: Long, val type: String,
                 val pair: String, var rate: BigDecimal, var amount: BigDecimal, var remaining: BigDecimal,
                 var have: BigDecimal, var willGet: BigDecimal, val fee: BigDecimal,
                 var feeVal: BigDecimal, val stock: String, var status: OrderStatus, var  attempt: Int = 0) {
    var arb_ids = mutableMapOf<Long, Int>()
    lateinit var saved: Future<Boolean>
    //var remaining = amount


    fun getLockCur() = pair.split("_")[if (type == "buy") 1 else 0]

    fun getFromCur() = getLockCur()

    fun getToCur() = pair.split("_")[if (type == "buy") 0 else 1]

    fun getLockAmount(amount: BigDecimal = remaining) = (if (type == "buy") (rate * amount).setScale(8, RoundingMode.DOWN) else amount).stripTrailingZeros()

    fun getPlayedAmount(amount: BigDecimal = remaining) = ((if (type == "buy") (amount * fee).setScale(8, RoundingMode.DOWN) else
        (rate * amount * fee).setScale(8, RoundingMode.DOWN)).stripTrailingZeros())
}