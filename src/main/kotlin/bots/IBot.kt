package bots

import stock.OrderUpdate

interface IBot {
    fun orderUpdate(update: OrderUpdate)
}