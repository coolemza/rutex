package bots

import mu.KLogger
import api.IStock

interface IState {
    val logger: KLogger
    val stockList: Map<String, IStock>
}