package stock

import data.Order
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.Kodein

abstract class WebSocketStock(kodein: Kodein, name: String) : Stock(kodein, name) {

    suspend fun parallelOrders(orders: List<Order>, code: (Order) -> OrderUpdate?): List<Job>? {
        return orders.map { order ->
            GlobalScope.launch {
                val orderUpdate = code(order)
                orderUpdate?.let { onActiveUpdate(it) } ?: logger.error("somthing goes wrong with $order")
            }
        }
    }
}