package build.linea.staterecover.clients.el

import build.linea.staterecover.BlockExtraData
import build.linea.staterecover.BlockL1RecoveredData
import build.linea.staterecover.TransactionL1RecoveredData
import build.linea.staterecover.clients.ExecutionLayerClient
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.status
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.vertx.junit5.VertxExtension
import kotlinx.datetime.Instant
import net.consensys.decodeHex
import net.consensys.linea.BlockNumberAndHash
import net.consensys.linea.BlockParameter
import net.consensys.linea.jsonrpc.client.RequestRetryConfig
import net.consensys.linea.jsonrpc.client.VertxHttpJsonRpcClientFactory
import net.javacrumbs.jsonunit.assertj.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@ExtendWith(VertxExtension::class)
class ExecutionLayerJsonRpcClientTest {
  private lateinit var client: ExecutionLayerClient
  private lateinit var wiremock: WireMockServer
  private lateinit var meterRegistry: SimpleMeterRegistry

  @BeforeEach
  fun setUp(vertx: io.vertx.core.Vertx) {
    wiremock = WireMockServer(WireMockConfiguration.options().dynamicPort())
    wiremock.start()

    meterRegistry = SimpleMeterRegistry()
    client = ExecutionLayerJsonRpcClient.create(
      rpcClientFactory = VertxHttpJsonRpcClientFactory(vertx, meterRegistry),
      endpoint = URI(wiremock.baseUrl()),
      requestRetryConfig = RequestRetryConfig(
        maxRetries = 3u,
        backoffDelay = 10.milliseconds,
        timeout = 2.seconds
      )
    )
  }

  @AfterEach
  fun tearDown() {
    wiremock.stop()
  }

  @Test
  fun `getBlockNumberAndHash`() {
    replyRequestWith(
      200,
      """
      {
          "jsonrpc": "2.0",
          "id": "53",
          "result": {
              "baseFeePerGas": "0x980b6e455",
              "blobGasUsed": "0x0",
              "difficulty": "0x0",
              "excessBlobGas": "0x0",
              "extraData": "0x6265617665726275696c642e6f7267",
              "gasLimit": "0x1c9c380",
              "gasUsed": "0x9428f2",
              "hash": "0xaeb67fef93febef9db0f83b7777c1d7444919e8a0c372fd0b2a022775118150e",
              "number": "0x13f2210",
              "size": "0xb4f8",
              "stateRoot": "0xf6ba9b93b98228e1d3217a7cb0fc4c5f1167854897add7b42f3fec8440234f8b",
              "timestamp": "0x670403ff",
              "transactions": [],
              "transactionsRoot": "0x3059e5603e750ea6edd7e43e3f6599d8584c936dba9840ae0b3767ce01b9810c",
              "uncles": [],
              "withdrawals": [],
              "withdrawalsRoot": "0xbbd14e124a749e443528b8cd53f988ee4e35a788bc1e8f60a1100d02eaa53bd0"
          }
      }
      """.trimIndent()
    )
    client.getBlockNumberAndHash(BlockParameter.Tag.LATEST).get()
      .also { response ->
        assertThat(response).isEqualTo(
          BlockNumberAndHash(
            number = 0x13f2210u,
            hash = "0xaeb67fef93febef9db0f83b7777c1d7444919e8a0c372fd0b2a022775118150e".decodeHex()
          )
        )
      }

    val requestJson = wiremock.serveEvents.serveEvents.first().request.bodyAsString
    assertThatJson(requestJson)
      .isEqualTo(
        """{
        "jsonrpc":"2.0",
        "id":"${'$'}{json-unit.any-number}",
        "method":"eth_getBlockByNumber",
        "params":["${'$'}{json-unit.regex}(latest|LATEST)", false]
        }"""
      )
  }

  @Test
  fun `lineaEngineImportBlocksFromBlob`() {
    replyRequestWith(
      200,
      """
      {
          "jsonrpc": "2.0",
          "id": "53",
          "result": null
      }
      """.trimIndent()
    )
    val block1 = BlockL1RecoveredData(
      blockNumber = 0xa001u,
      blockHash = "0xa011".decodeHex(),
      coinbase = "0xa022".decodeHex(),
      blockTimestamp = Instant.parse("2024-07-01T11:22:33Z"),
      gasLimit = 0x1c9c380u,
      difficulty = 0u,
      extraData = BlockExtraData(beneficiary = "0x6265617665726275696c642e6f7267".decodeHex()),
      transactions = listOf(
        TransactionL1RecoveredData(
          type = 0x01u,
          nonce = 0xb010u,
          maxPriorityFeePerGas = "b010011".toBigInteger(16),
          maxFeePerGas = "b0100ff".toBigInteger(16),
          gasLimit = 0xb0100aau,
          from = "0xb011".decodeHex(),
          to = "0xb012".decodeHex(),
          value = 123.toBigInteger(),
          data = "0xb013".decodeHex(),
          accessList = listOf(
            TransactionL1RecoveredData.AccessTuple(
              address = "0xb014".decodeHex(),
              storageKeys = listOf("0xb015".decodeHex(), "0xb015".decodeHex())
            )
          )
        )
      )
    )

    assertThat(client.lineaEngineImportBlocksFromBlob(listOf(block1)).get()).isEqualTo(Unit)

    val requestJson = wiremock.serveEvents.serveEvents.first().request.bodyAsString
    assertThatJson(requestJson)
      .isEqualTo(
        """{
        "jsonrpc":"2.0",
        "id":"${'$'}{json-unit.any-number}",
        "method":"linea_engine_importBlocksFromBlob",
        "params":[{
          "blockNumber": "0xa001",
          "blockHash": "0xa011",
          "coinbase": "0xa022",
          "blockTimestamp": "2024-07-01T11:22:33Z",
          "gasLimit": "0x1c9c380",
          "difficulty": "0x0",
          "extraData": {
            "beneficiary": "0x6265617665726275696c642e6f7267"
          },
          "transactions": [{
            "type": "0x01",
            "nonce": "0xb010",
            "maxPriorityFeePerGas": "0xb010011",
            "maxFeePerGas": "0xb0100ff",
            "gasLimit": "0xb0100aa",
            "from": "0xb011",
            "to": "0xb012",
            "value": "0x7b",
            "data": "0xb013",
            "accessList": [
                {
                    "address": "0xb014",
                    "storageKeys": [
                        "0xb015",
                        "0xb015"
                    ]
                }
            ]
        }]
      }]
      }"""
      )
  }

  private fun replyRequestWith(statusCode: Int, body: String?) {
    wiremock.stubFor(
      post("/")
        .withHeader("Content-Type", containing("application/json"))
        .willReturn(
          status(statusCode)
            .withHeader("Content-type", "text/plain")
            .apply { if (body != null) withBody(body) }
        )
    )
  }
}
