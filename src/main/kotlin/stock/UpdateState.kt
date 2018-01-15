package stock

import data.DepthBook
import utils.getUpdate

class UpdateState(val state_id: String, val url: Map<String, String>, val stock: IStock) : Runnable {
    val stateNew = DepthBook()
    val stateCur = DepthBook()

    override fun run() { //TODO: split pare column in db
        stateNew.clear()
        stock.getDepth(url, state_id, stateNew)?.let {
            getUpdate(stateCur, it, stock.state.depthLimit)?.let {
                stateCur.replace(stateNew)
                stock.state.OnStateUpdate(it)
            }
        }
    }
}