package stock

import com.github.salomonbrys.kodein.Kodein
import data.Order
import database.StockKey
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch

abstract class WebSocketStock(kodein: Kodein, name: String) : Stock(kodein, name) {

    suspend fun parallelOrders(orders: List<Order>, code: (Order) -> OrderUpdate?): List<Job>? {
        return orders.map { order ->
            launch {
                val orderUpdate = code(order)
                orderUpdate?.let { onActiveUpdate(it) } ?: logger.error("somthing goes wrong with $order")
            }
        }
    }
}