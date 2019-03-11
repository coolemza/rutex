package api.connectors

interface IConnector {

    suspend fun start()
    suspend fun stop()

    suspend fun reconnect()
}
