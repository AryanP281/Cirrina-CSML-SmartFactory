package at.ac.uibk.dps.cirrina.execution.`object`

import com.codahale.metrics.CsvReporter
import com.codahale.metrics.Meter
import com.codahale.metrics.MetricRegistry
import io.zenoh.Config
import io.zenoh.Zenoh
import io.zenoh.ext.HistoryConfig
import io.zenoh.ext.RecoveryConfig
import io.zenoh.ext.RecoveryMode
import io.zenoh.keyexpr.KeyExpr
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

val zenohConfig =
  System.getenv("ZENOH_CONFIG_URI")?.let { path -> Config.fromFile(File(path)).getOrThrow() }
    ?: Config.default()

val zenohSession = Zenoh.open(zenohConfig).getOrThrow()

val metricsPeriod = System.getenv("METRICS_PERIOD")?.toLong() ?: 1L
val metricsRegistry = MetricRegistry()

val partArrivals: Meter = metricsRegistry.meter("partArrivals")

val partsCompleted: Meter = metricsRegistry.meter("partsCompleted")

val logger = LoggerFactory.getLogger("at.ac.uibk.dps.cirrina.execution.object.MetricsCollectorKt")

val shutdownLatch = CountDownLatch(1)

fun main() {

  CsvReporter.forRegistry(metricsRegistry)
    .build(File("./metrics"))
    .start(metricsPeriod, TimeUnit.SECONDS)

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/peripheral/eBeamInterruptedStart").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { _ -> partArrivals.mark() },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/arm/eAssemblyComplete").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { _ -> partsCompleted.mark() },
    )
    .getOrThrow()

  logger.info("Metrics collector started")

  shutdownLatch.await()
}
