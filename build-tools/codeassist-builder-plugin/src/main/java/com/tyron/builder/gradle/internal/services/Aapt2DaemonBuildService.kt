package com.tyron.builder.gradle.internal.services

import com.android.SdkConstants
import com.android.annotations.concurrency.GuardedBy
import com.android.utils.ILogger
import com.google.common.io.Closer
import com.tyron.builder.gradle.internal.LoggerWrapper
import com.tyron.builder.gradle.internal.workeractions.WorkerActionServiceRegistry
import com.tyron.builder.gradle.options.ProjectOptions
import com.tyron.builder.ide.common.ProcessException
import com.tyron.builder.internal.aapt.v2.Aapt2
import com.tyron.builder.internal.aapt.v2.Aapt2DaemonImpl
import com.tyron.builder.internal.aapt.v2.Aapt2DaemonManager
import com.tyron.builder.internal.aapt.v2.Aapt2DaemonTimeouts
import com.tyron.builder.plugin.options.SyncOptions
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import java.io.Closeable
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Service registry used to store AAPT2 daemon services so they are accessible from the worker
 * actions.
 */
var aapt2DaemonServiceRegistry: WorkerActionServiceRegistry = WorkerActionServiceRegistry()


/** Intended for use from worker actions. */
@Throws(ProcessException::class, IOException::class)
fun <T: Any>useAaptDaemon(
    aapt2ServiceKey: Aapt2DaemonServiceKey,
    serviceRegistry: WorkerActionServiceRegistry = aapt2DaemonServiceRegistry,
    block: (Aapt2DaemonManager.LeasedAaptDaemon) -> T) : T {
    return getAaptDaemon(aapt2ServiceKey, serviceRegistry).use(block)
}



/** Intended for use from java worker actions. */
@JvmOverloads
fun getAaptDaemon(
    aapt2ServiceKey: Aapt2DaemonServiceKey,
    serviceRegistry: WorkerActionServiceRegistry = aapt2DaemonServiceRegistry)
        : Aapt2DaemonManager.LeasedAaptDaemon =
    serviceRegistry.getService(aapt2ServiceKey).service.leaseDaemon()

data class Aapt2DaemonServiceKey(val version: String): WorkerActionServiceRegistry.ServiceKey<Aapt2DaemonManager> {
    override val type: Class<Aapt2DaemonManager> get() = Aapt2DaemonManager::class.java
}

/** Build service used to access AAPT2 daemons. */
abstract class Aapt2DaemonBuildService : BuildService<Aapt2DaemonBuildService.Parameters>,
    AutoCloseable {

    private val registeredServices = mutableSetOf<Aapt2DaemonServiceKey>()
    private val services = mutableMapOf<Aapt2DaemonServiceKey, Aapt2DaemonManager>()
    private val closer = Closer.create()
    private val logger: ILogger = LoggerWrapper.getLogger(this.javaClass)

    fun getLeasingAapt2(aapt2Input: Aapt2Input) : Aapt2 {
        val manager = getManager(Aapt2DaemonServiceKey(aapt2Input.version.get()), getAapt2ExecutablePath(aapt2Input))
        val leasingAapt2 = manager.leasingAapt2Daemon
        return PartialInProcessResourceProcessor(leasingAapt2)
    }

    @Synchronized
    fun registerAaptService(
        aapt2Version: String,
        aaptExecutablePath: Path
    ): Aapt2DaemonServiceKey {
        val key = Aapt2DaemonServiceKey(aapt2Version)

        if (registeredServices.add(key)) {
            val manager = getManager(key, aaptExecutablePath)
            closer.register(aapt2DaemonServiceRegistry.registerServiceAsCloseable(key, manager))
        }
        return key
    }

    @Synchronized
    private fun getManager(key: Aapt2DaemonServiceKey, aaptExecutablePath: Path) : Aapt2DaemonManager {
        return services.getOrPut(key) {
            Aapt2DaemonManager(
                logger = logger,
                daemonFactory = { displayId ->
                    Aapt2DaemonImpl(
                        displayId = "#$displayId",
                        aaptExecutable = aaptExecutablePath,
                        daemonTimeouts = daemonTimeouts,
                        logger = logger
                    )
                },
                expiryTime = daemonExpiryTimeSeconds,
                expiryTimeUnit = TimeUnit.SECONDS,
                listener = Aapt2DaemonManagerMaintainer()
            )
        }.also { closer.register(Closeable { it.shutdown() }) }
    }

    fun getAapt2ExecutablePath(aapt2: Aapt2Input): Path {
        return aapt2.binaryDirectory.singleFile.toPath().resolve(SdkConstants.FN_AAPT2).also {
            if (!Files.exists(it) && !Files.isSymbolicLink(it)) {
                throw InvalidUserDataException(
                    "Specified AAPT2 executable does not exist: $it. "
                            + "Must supply one of aapt2 from maven or custom location."
                )
            }
        }
    }

    override fun close() {
        closer.close()
    }

    abstract class Parameters: BuildServiceParameters {
        abstract val errorFormatMode: Property<SyncOptions.ErrorFormatMode>
    }

    class RegistrationAction(project: Project, val projectOptions: ProjectOptions) :
        ServiceRegistrationAction<Aapt2DaemonBuildService, Parameters>(
            project,
            Aapt2DaemonBuildService::class.java,
            computeMaxAapt2Daemons(projectOptions)
        ) {
        override fun configure(parameters: Parameters) {
            parameters.errorFormatMode.set(SyncOptions.getErrorFormatMode(projectOptions))
        }
    }
}

/**
 * An AAPT2 input for use in a task or a transform, for use with [org.gradle.api.tasks.Nested]
 *
 * Use [ProjectServices.initializeAapt2Input] to initialize it.
 */
interface Aapt2Input {
    @get:Internal
    val buildService: Property<Aapt2DaemonBuildService>

    @get:Internal
    val threadPoolBuildService: Property<Aapt2ThreadPoolBuildService>

    @get:Input
    val version: Property<String>

    @get:Internal
    val binaryDirectory: ConfigurableFileCollection

    /** The max worker count from Gradle, for bucketing in-process resource compilation for workers */
    @get:Internal
    val maxWorkerCount: Property<Int>

    /** The max number of AAPT2 daemons, used for bucketing `aapt2 compile` calls for workers */
    @get:Internal
    val maxAapt2Daemons: Property<Int>
}

@Deprecated("Instead use Aapt2Input.use inside a ProfileAwareWorkAction")
fun Aapt2Input.registerAaptService(): Aapt2DaemonServiceKey =
    this.buildService.get().registerAaptService(
        version.get(),
        buildService.get().getAapt2ExecutablePath(this)
    )

fun Aapt2Input.getErrorFormatMode(): SyncOptions.ErrorFormatMode {
    return this.buildService.get().parameters.errorFormatMode.get()
}

fun Aapt2Input.getAapt2Executable(): Path {
    return buildService.get().getAapt2ExecutablePath(this)
}

fun Aapt2Input.getLeasingAapt2(): Aapt2 {
    return buildService.get().getLeasingAapt2(this)
}

/**
 * Responsible for scheduling maintenance on the Aapt2Service.
 *
 * There are three ways the daemons can all be shut down.
 * 1. An explicit call of [Aapt2DaemonManager.shutdown]. (e.g. at the end of each build invocation.)
 * 2. All the daemons being timed out by the logic in [Aapt2DaemonManager.maintain].
 *    Calls to maintain are scheduled below, and only while there are daemons running to avoid
 *    leaking a thread.
 * 3. The JVM shutdown hook, which like (2) is only kept registered while daemons are running.
 */
private class Aapt2DaemonManagerMaintainer : Aapt2DaemonManager.Listener {
    @GuardedBy("this")
    private var maintainExecutor: ScheduledExecutorService? = null
    @GuardedBy("this")
    private var maintainAction: ScheduledFuture<*>? = null
    @GuardedBy("this")
    private var shutdownHook: Thread? = null

    @Synchronized
    override fun firstDaemonStarted(manager: Aapt2DaemonManager) {
        maintainExecutor = Executors.newSingleThreadScheduledExecutor()
        maintainAction = maintainExecutor!!.
        scheduleAtFixedRate(
            manager::maintain,
            daemonExpiryTimeSeconds + maintenanceIntervalSeconds,
            maintenanceIntervalSeconds,
            TimeUnit.SECONDS)
        shutdownHook = Thread { shutdown(manager) }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    @Synchronized
    override fun lastDaemonStopped() {
        maintainAction!!.cancel(false)
        maintainExecutor!!.shutdown()
        maintainAction = null
        maintainExecutor = null
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook!!)
            shutdownHook = null
        }
    }

    private fun shutdown(manager: Aapt2DaemonManager) {
        // Unregister the hook, as shutting down the daemon manager will trigger lastDaemonStopped()
        // and removeShutdownHook throws if called during shutdown.
        synchronized(this) {
            this.shutdownHook = null
        }
        manager.shutdown()
    }
}

private val daemonTimeouts = Aapt2DaemonTimeouts()
private val daemonExpiryTimeSeconds = TimeUnit.MINUTES.toSeconds(3)
private val maintenanceIntervalSeconds = TimeUnit.MINUTES.toSeconds(1)
