package bots

import api.OrderUpdate

interface IBot {
    fun orderUpdate(update: OrderUpdate)
}