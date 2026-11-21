package net.lockney.tabula.ha

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.lockney.tabula.core.screensaver.EntityNeeds
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue

/**
 * The screensaver's entity data over a real socket (packet P3.5, ADR 0017):
 * the real TLP client and [EntityDataConnection] against mockdevice's test
 * server, which speaks HA core's compressed `subscribe_entities` format (`a`,
 * `c` with `+`/`-`, `r`), `weather/subscribe_forecast` and the display-data
 * commands on the same socket as TLP. Covers the explicit list, deltas,
 * unavailable entities, a dropped connection and the resubscription on the
 * next one, close on dismissal, and that no credential reaches the log.
 *
 * Skips like [PythonServerTest] when the checkout or uv is missing.
 */
class EntityDataServerTest {
    private val client = OkHttpClient()

    @Test
    fun `subscribes, follows deltas, survives a dropped connection and closes`() {
        val root = findRepoRoot()
        assumeTrue("repo checkout not found from ${File(".").absolutePath}", root != null)
        assumeTrue("uv is not available", uvAvailable())
        val server = TestServerProcess(root!!)
        try {
            server.awaitListening()
            scenario(server.url)
        } finally {
            server.close()
        }
    }

    private fun scenario(url: String): Unit = runBlocking {
        withTimeout(90_000) {
            val http = OkHttpExchange(client)
            suspend fun hook(path: String, body: String) {
                val reply = http.post("$url/test/$path", "application/json", body)
                assertEquals(200, reply.status, "$path: ${reply.body}")
            }
            suspend fun report() = Json.parseToJsonElement(http.get("$url/test/report").body).jsonObject

            // pair (P2.1's path) so the client holds a real refresh token
            val pairing = PairingClient(http)
            val deviceId = "0f0e0d0c-0000-4000-8000-000000000035"
            val attempt = pairing.newAttempt()
            pairing.start(url, deviceId, attempt, "JUnit", "jvm")
            hook("confirm", """{"device_id":"$deviceId","code":"${attempt.code}"}""")
            val refresh = (pairing.claim(url, deviceId, attempt) as ClaimOutcome.Paired).credential.refreshToken

            // HA's side before the screensaver opens
            hook("entity", """{"entity_id":"lock.front","state":"locked","attributes":{"friendly_name":"Front","battery":80}}""")
            hook("entity", """{"entity_id":"weather.home","state":"rainy"}""")
            hook("forecast", """{"entity_id":"weather.home","forecast_type":"daily","forecast":[{"condition":"rainy","temperature":12}]}""")
            hook(
                "display",
                """{"registry":[{"ei":"lock.front","pl":"demo"}],
                   "translations":{"component.lock.entity_component._.state.locked":"Locked",
                                   "component.lock.entity_component._.state.unlocked":"Unlocked"},
                   "icons":{"entity_component":{"lock":{"_":{"default":"mdi:lock"}}}}}""",
            )

            val registered = CompletableDeferred<Unit>()
            val tlp = TlpClient(
                TlpClient.Config(
                    haUrl = url,
                    deviceId = deviceId,
                    appVersion = "0.1.0-test",
                    manufacturer = "Tabula",
                    model = "jvm-test",
                    osVersion = "jvm",
                    capabilities = listOf("screensaver"),
                    settingsRevision = { 0 },
                    refreshToken = { refresh },
                    state = FakeState(),
                    commands = FakeCommands(),
                ),
                TokenRefresher(http),
                OkHttpWsTransportFactory(client),
            )
            val events = object : TlpClient.Events {
                override fun onRegistered(result: net.lockney.tabula.core.tlp.RegisterResultData) {
                    registered.complete(Unit)
                }

                override fun onDisconnected() = Unit

                override fun onRevoked() = Unit

                override fun onRejected(code: String, message: String) = Unit
            }
            val session = launch { tlp.run(events) }
            withTimeout(20_000) { registered.await() }

            val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
            val data = EntityDataConnection(tlp.channel, this, log = { logs += it })
            val needs = EntityNeeds(
                entityIds = listOf("lock.front", "binary_sensor.missing", "weather.home"),
                forecasts = listOf(EntityNeeds.Forecast("weather.home")),
            )
            data.start(needs)

            // initial snapshot: the explicit list, missing entities unavailable
            data.states.first { it["lock.front"]?.state == "locked" }
            assertTrue(data.states.value.getValue("binary_sensor.missing").missing)
            assertEquals(needs.entityIds, data.states.value.keys.toList())
            data.forecasts.first { it.isNotEmpty() }
            data.display.first { it.registry.isNotEmpty() && it.translations.isNotEmpty() }
            assertEquals("Locked", data.display.value.stateText(data.states.value.getValue("lock.front")))
            assertEquals("mdi:lock", data.display.value.icon(data.states.value.getValue("lock.front")))
            val subs = report()["devices"]!!.jsonObject[deviceId]!!.jsonObject["ha_subscriptions"]!!.jsonArray
            assertEquals(2, subs.size, "one entity and one forecast subscription: $subs")
            assertEquals(
                """["lock.front","binary_sensor.missing","weather.home"]""",
                subs.first { it.jsonObject["type"].toString() == "\"subscribe_entities\"" }.jsonObject["entity_ids"].toString(),
            )

            // deltas: a change with a removed attribute, a late addition, a removal
            hook("entity", """{"entity_id":"lock.front","state":"unlocked","attributes":{"friendly_name":"Front"}}""")
            data.states.first { it["lock.front"]?.state == "unlocked" }
            assertFalse("battery" in data.states.value.getValue("lock.front").attributes)
            hook("entity", """{"entity_id":"binary_sensor.missing","state":"on"}""")
            data.states.first { it["binary_sensor.missing"]?.state == "on" }
            hook("entity", """{"entity_id":"weather.home","remove":true}""")
            data.states.first { it["weather.home"]?.missing == true }
            hook("forecast", """{"entity_id":"weather.home","forecast_type":"daily","forecast":[{"condition":"sunny"},{"condition":"cloudy"}]}""")
            data.forecasts.first { it.values.single().size == 2 }
            hook("entity", """{"entity_id":"weather.home","state":"sunny"}""")
            data.states.first { it["weather.home"]?.state == "sunny" }

            // the connection drops: last-known data stays, the TLP client
            // reconnects with backoff and the subscriptions are made again
            hook("drop", """{"device_id":"$deviceId"}""")
            data.subscribed.first { !it }
            assertEquals("unlocked", data.states.value.getValue("lock.front").state)
            data.subscribed.first { it }
            withTimeout(10_000) {
                while (report()["devices"]!!.jsonObject[deviceId]!!.jsonObject["ha_subscriptions"]!!.jsonArray.size < 2) {
                    kotlinx.coroutines.delay(50)
                }
            }
            hook("entity", """{"entity_id":"lock.front","state":"jammed"}""")
            data.states.first { it["lock.front"]?.state == "jammed" }

            // dismissal closes both subscriptions on the server
            data.stop()
            withTimeout(10_000) {
                while (report()["devices"]!!.jsonObject[deviceId]!!.jsonObject["ha_subscriptions"]!!.jsonArray.isNotEmpty()) {
                    kotlinx.coroutines.delay(50)
                }
            }
            val commands = report()["ha_commands"]!!.jsonArray.map { it.toString().trim('"') }
            assertEquals(2, commands.count { it == "subscribe_entities" }, "once per connection: $commands")
            assertEquals(2, commands.count { it == "weather/subscribe_forecast" }, "once per connection: $commands")
            assertEquals(2, commands.count { it == "unsubscribe_events" }, "closed on dismissal: $commands")

            // TLP stayed clean throughout, and no credential reached the log
            assertEquals("[]", report()["problems"].toString())
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.none { refresh in it || "access_token" in it || "Bearer" in it }, "logs: $logs")

            session.cancelAndJoin()
        }
    }
}
