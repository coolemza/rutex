package data

import java.math.BigDecimal

data class Depth(var rate: BigDecimal = BigDecimal.ZERO, var amount: BigDecimal = BigDecimal.ZERO) {
    constructor(depth: Depth) : this(depth.rate, depth.amount)
    constructor(rate: String, amount: String): this(BigDecimal(rate), BigDecimal(amount))

    fun replace(rate: BigDecimal, amount: BigDecimal) {
        this.rate = rate
        this.amount = amount
    }

    fun replace(rate: String, amount: String) = replace(BigDecimal(rate), BigDecimal(amount))

    fun replace(depth: Depth) = replace(depth.rate, depth.amount)
}