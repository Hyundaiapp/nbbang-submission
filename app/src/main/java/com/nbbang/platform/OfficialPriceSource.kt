package com.nbbang.platform

import android.util.Log
import com.nbbang.settle.OfficialPrice
import com.nbbang.settle.Opinet
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * F-C07 당일 공시 평균 유가. 오피넷 전국 주유소 평균가격(avgAllPrice.do)을 부른다.
 * 키가 없거나, 네트워크가 안 되거나, 키가 틀려 빈 목록이 오면 null을 돌려주고 부르는 쪽이 최근 조회값을 쓴다.
 */
class OfficialPriceSource(private val key: String) {
    val enabled: Boolean get() = key.isNotBlank()

    suspend fun fetch(): List<OfficialPrice>? {
        if (!enabled) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(Opinet.url(key)).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) error("HTTP ${conn.responseCode}")
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Opinet.parse(body).ifEmpty { error("빈 응답(키 확인)") }
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "공시 유가 조회 실패: ${it.message}") }
                .onSuccess { list -> Log.i(TAG, "공시 유가 ${list.joinToString { "${it.fuelKind} ${it.won}원(${it.date})" }}") }
                .getOrNull()
        }
    }

    private companion object {
        const val TAG = "RideOfficial"
        const val TIMEOUT_MS = 8_000
    }
}
