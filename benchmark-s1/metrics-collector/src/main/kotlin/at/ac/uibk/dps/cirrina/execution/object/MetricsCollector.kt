package at.ac.uibk.dps.cirrina.execution.`object`

import at.ac.uibk.dps.cirrina.execution.util.Serializer
import at.ac.uibk.dps.cirrina.spec.Event
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

val zenohConfig =
  System.getenv("ZENOH_CONFIG_URI")?.let { path -> Config.fromFile(File(path)).getOrThrow() }
    ?: Config.default()

val zenohSession = Zenoh.open(zenohConfig).getOrThrow()

val partAssemblyStartTimes = ConcurrentHashMap<Long, Long>()
val assemblyControllerStartTimes = ConcurrentHashMap<Long, Long>()
val beltMovementStartTimes = ConcurrentHashMap<Long, Long>()
val armHandlngStartTimes = ConcurrentHashMap<Long, Long>()

val metricsPeriod = System.getenv("METRICS_PERIOD")?.toLong() ?: 1L
val metricsRegistry = MetricRegistry()

val partArrivals: Meter = metricsRegistry.meter("partArrivals")
val partsCompleted: Meter = metricsRegistry.meter("partsCompleted")

val metricsDir: Path = Path.of("./metrics")
val assemblyTimesFile: Path = metricsDir.resolve("partAssemblyTimes.csv")
val assemblyControllerTimesFile: Path = metricsDir.resolve("assemblyControllerTimes.csv")
val beltMovementTimesFile: Path = metricsDir.resolve("beltMovementTimes.csv")
val armHandlingTimesFile: Path = metricsDir.resolve("armHandlingTimes.csv")

val assemblyTimesCsvLock = Any()
val assemblyControllerTimesCsvLock = Any()
val beltMovementTimesCsvLock = Any()
val armHandlingTimesCsvLock = Any()

val logger = LoggerFactory.getLogger("at.ac.uibk.dps.cirrina.execution.object.MetricsCollectorKt")

val shutdownLatch = CountDownLatch(1)

fun main() {

  Files.createDirectories(metricsDir)

  if (!Files.exists(assemblyTimesFile)) {
    Files.writeString(
      assemblyTimesFile,
      "partId,startTimeNs,endTimeNs,assemblyTimeNs\n",
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
  }

  if (!Files.exists(assemblyControllerTimesFile)) {
    Files.writeString(
      assemblyControllerTimesFile,
      "partId,startTimeNs,endTimeNs,assemblyControllerTimeNs\n",
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
  }

  if (!Files.exists(beltMovementTimesFile)) {
    Files.writeString(
      beltMovementTimesFile,
      "partId,startTimeNs,endTimeNs,beltMovementTimeNs\n",
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
  }

  if (!Files.exists(armHandlingTimesFile)) {
    Files.writeString(
      armHandlingTimesFile,
      "partId,startTimeNs,endTimeNs,armHandlingTimeNs\n",
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    )
  }

  CsvReporter.forRegistry(metricsRegistry)
    .build(metricsDir.toFile())
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
      callback = { sample ->
        partsCompleted.mark()

        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        val partAssemblyStartTime = partAssemblyStartTimes.remove(partId)

        if (partAssemblyStartTime != null) {
          val assemblyTime = (event.emittedTime - partAssemblyStartTime).coerceAtLeast(0L)

          synchronized(assemblyTimesCsvLock) {
            Files.writeString(
              assemblyTimesFile,
              "$partId,$partAssemblyStartTime,${event.emittedTime},$assemblyTime\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            )
          }
        }
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/assemblyController/ePartAssemblyInitiated").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        partAssemblyStartTimes[partId] = event.emittedTime
        assemblyControllerStartTimes[partId] = event.emittedTime
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/assemblyController/ePartProcessingComplete").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        val partAssemblyStartTime = assemblyControllerStartTimes.remove(partId)

        if (partAssemblyStartTime != null) {
          val assemblyControllerTime = (event.emittedTime - partAssemblyStartTime).coerceAtLeast(0L)

          synchronized(assemblyControllerTimesCsvLock) {
            Files.writeString(
              assemblyControllerTimesFile,
              "$partId,$partAssemblyStartTime,${event.emittedTime},$assemblyControllerTime\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            )
          }
        } else {
          logger.warn("No assembly start time found for part $partId")
        }
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/conveyorBelt/ePartMovementInitiated").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        beltMovementStartTimes[partId] = event.emittedTime
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/conveyorBelt/ePartMovingComplete").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        val partMovementStartTime = beltMovementStartTimes.remove(partId)

        if (partMovementStartTime != null) {
          val movementTime = (event.emittedTime - partMovementStartTime).coerceAtLeast(0L)

          synchronized(beltMovementTimesCsvLock) {
            Files.writeString(
              beltMovementTimesFile,
              "$partId,$partMovementStartTime,${event.emittedTime},$movementTime\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            )
          }
        } else {
          logger.warn("No movement start time found for part $partId")
        }
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/conveyorBelt/eArmPickup").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        armHandlngStartTimes.merge(partId, event.emittedTime) { old, new -> minOf(old, new) }
      },
    )
    .getOrThrow()

  zenohSession
    .declareAdvancedSubscriber(
      KeyExpr.tryFrom("events/arm/eArmCompletedProcessing").getOrThrow(),
      subscriberDetection = true,
      recoveryConfig = RecoveryConfig(RecoveryMode.Heartbeat),
      historyConfig = HistoryConfig(detectLatePublishers = true),
      callback = { sample ->
        val event = Serializer.deserialize<Event>(sample.payload.toBytes())

        val partId = event.data.first { it.name == "partId" }.value as Long

        val partHandlingStartTime = armHandlngStartTimes.remove(partId)

        if (partHandlingStartTime != null) {
          val handlingTime = (event.emittedTime - partHandlingStartTime).coerceAtLeast(0L)

          synchronized(armHandlingTimesCsvLock) {
            Files.writeString(
              armHandlingTimesFile,
              "$partId,$partHandlingStartTime,${event.emittedTime},$handlingTime\n",
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            )
          }
        } else {
          logger.warn("No handling start time found for part $partId")
        }
      },
    )
    .getOrThrow()

  logger.info("Metrics collector started")

  shutdownLatch.await()
}
