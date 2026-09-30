package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.ManagementAct
import io.github.kzagoris.proxenos.coreapi.Origin
import io.github.kzagoris.proxenos.coreapi.RuntimeEvent
import io.github.kzagoris.proxenos.coreapi.WorkspaceManagement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * What a frontend does, in one place: the acts that change a registration go to the
 * registry, and the acts that are about the Runtime's own account go where they belong. The
 * management client in `control` implements this same interface over the control socket, so
 * the TUI now and a GUI later program against the type the core implements in-process.
 *
 * It is deliberately a different type from `WorkspaceOperations`: "could a conversation raise
 * an Access Level?" is then answered by the compiler rather than by a runtime check.
 */
class RuntimeManagement(
  private val registry: WorkspaceRegistry,
  private val activity: Activity,
  private val tunnel: Tunnel,
  private val pipeline: WorkspaceOperationsPipeline,
  private val connector: ConnectorAcknowledgement,
  /**
   * The same feed [registry], [activity] and [connector] publish to; the tunnel's transitions are
   * carried into it here.
   */
  private val feed: RuntimeFeed,
  /**
   * What ends the process once [ManagementAct.Stop] has ended everything running. The core
   * owns no process, so the composition root says what exiting means; a test says nothing.
   */
  private val exit: () -> Unit = {},
) : WorkspaceManagement {
  /**
   * The Frontend view of the one pipeline, stamped here once: TryOperation is admitted exactly
   * as ChatGPT's call would be, and recorded as the frontend's.
   */
  private val tried = pipeline.operationsFor(Origin.Frontend)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  init {
    // Before anyone can attach, so no snapshot carries a status the tunnel never had. The
    // collector then replays the same one, which applying twice leaves as it was.
    feed.publish(RuntimeEvent.Change.RuntimeChanged(tunnel.status()))
    scope.launch { tunnel.observe().collect { feed.publish(RuntimeEvent.Change.RuntimeChanged(it)) } }
    // Words, carried the same way; the collector starts with the current value.
    scope.launch { tunnel.connectingWords().collect { feed.publish(RuntimeEvent.Change.ConnectingWordsChanged(it)) } }
  }

  override fun observe(): Flow<RuntimeEvent> = feed.observe()

  @Suppress("UNCHECKED_CAST") // Each sealed act fixes its result type.
  override suspend fun <R> perform(act: ManagementAct<R>): R = when (act) {
    is ManagementAct.OnRegistry -> registry.perform(act)
    // Appending, not flagging: the moment an unattended command was noticed becomes part of
    // the account, and the entry it refers to is not touched.
    is ManagementAct.Acknowledge -> activity.acknowledge(act.entry) as R
    // Not Activity's: creating a connector in a browser is not an Operation (ADR 0007).
    ManagementAct.AcknowledgeConnector -> connector.acknowledge() as R
    // The link, and nothing else: no registration, no Access Level and no running Operation is
    // reachable from here, which is what keeps Disconnect from ever meaning Revoke or Stop.
    ManagementAct.Disconnect -> tunnel.disconnect() as R
    ManagementAct.Connect -> tunnel.connect() as R
    // Everything running first, in one grace period, then the link, then the process. The
    // pipeline refuses new calls before it reaps, so nothing arriving over a dying tunnel starts.
    ManagementAct.Stop -> {
      pipeline.stop()
      tunnel.stop()
      scope.cancel()
      exit()
      Unit as R
    }
    is ManagementAct.StopOperation -> {
      require(pipeline.stopOperation(act.entry)) { "No command is running as entry ${act.entry.value}" }
      Unit as R
    }
    is ManagementAct.TryOperation<*> -> tried.perform(act.op) as R
    is ManagementAct.ReadOutput -> pipeline.outputOf(act.entry) as R
  }
}
