package utils

import org.slf4j.Logger

interface IHttp {
    suspend fun post(logger: Logger, url: String, hdrs: Map<String, String>? = null, postData: String = "", timeOut: Long = 2000): String?
    suspend fun get(logger: Logger, url: String, timeOut: Long = 2000): String?
}