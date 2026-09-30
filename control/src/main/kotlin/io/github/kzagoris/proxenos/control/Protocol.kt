package io.github.kzagoris.proxenos.control

import io.github.kzagoris.proxenos.coreapi.*
import java.io.BufferedReader
import java.io.Writer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * The control protocol: one JSON document per line over a Unix socket, one connection
 * per act or per observer.
 *
 * Newline framing is safe because JSON escapes every newline inside a string, so a line is
 * always exactly one frame. A connection per act, rather than one multiplexed connection, is
 * what lets any number of frontends attach with nothing to coordinate: a connection has one
 * question and one answer, or one observer and its stream, and ends.
 */

/** What a frontend sends, once, as the first and only line of a connection. */
@Serializable
internal sealed interface Request {
  /**
   * The act as JSON rather than as a typed field: an act's type argument is its *result*, which
   * is phantom on the wire, and [actSerializer] is the one place that says so.
   */
  @Serializable
  data class Perform(val act: JsonElement) : Request

  @Serializable
  data object Observe : Request
}

/** What the Runtime sends back. */
@Serializable
internal sealed interface Reply {
  /** The act's result, in the shape [resultSerializer] gives for that act. */
  @Serializable
  data class Done(val result: JsonElement) : Reply

  /**
   * The act was not done, or the peer is not this Linux user. [invalidArgument] keeps the core's
   * distinction between "you named something that does not exist" and every other refusal, so
   * the client throws what the core would have thrown.
   */
  @Serializable
  data class Refused(val reason: String, val invalidArgument: Boolean = false) : Reply

  /** One step of an observer's stream: a snapshot first, then changes. */
  @Serializable
  data class Event(val event: RuntimeEvent) : Reply
}

/** `@type` rather than `type`: no domain type then has to avoid a property of that name. */
internal val wire = Json { classDiscriminator = "@type" }

/**
 * Every act, whatever its result. The argument to [ManagementAct.serializer] is the result type,
 * which no act carries as data — it is only the compiler's record of what [perform] returns —
 * so any serializer does, and [resultSerializer] is what reads a result back.
 */
@Suppress("UNCHECKED_CAST")
internal val actSerializer: KSerializer<ManagementAct<*>> =
  ManagementAct.serializer(Unit.serializer()) as KSerializer<ManagementAct<*>>

/**
 * What [act] returns, as a serializer. The one table in the protocol, and exhaustive: a new act
 * that is not added here does not compile.
 */
@Suppress("UNCHECKED_CAST")
internal fun <R> resultSerializer(act: ManagementAct<R>): KSerializer<R> = when (act) {
  is ManagementAct.Register, is ManagementAct.Rename, is ManagementAct.SetLevel, is ManagementAct.Reconfirm ->
    Workspace.serializer()
  is ManagementAct.Forget, is ManagementAct.Acknowledge, ManagementAct.AcknowledgeConnector,
  ManagementAct.Disconnect, ManagementAct.Connect, ManagementAct.Stop, is ManagementAct.StopOperation -> Unit.serializer()
  is ManagementAct.TryOperation<*> -> Outcome.serializer(valueSerializer(act.op))
  is ManagementAct.ReadOutput -> RunningCommand.serializer().nullable
} as KSerializer<R>

/** What an Ok outcome of [op] carries. Exhaustive for the same reason as [resultSerializer]. */
private fun valueSerializer(op: Operation<*>): KSerializer<*> = when (op) {
  Operation.ListWorkspaces -> ListSerializer(WorkspaceListing.serializer())
  is Operation.ReadFile -> FileContent.serializer()
  is Operation.ListDirectory -> DirectoryListing.serializer()
  is Operation.Search -> SearchResults.serializer()
  is Operation.GitStatus -> GitStatusReport.serializer()
  is Operation.GitDiff -> GitDiffReport.serializer()
  is Operation.GitLog -> GitLogReport.serializer()
  is Operation.WriteFile, is Operation.EditFile -> FileWritten.serializer()
  is Operation.RunCommand -> CommandReply.serializer()
  is Operation.GetResult -> Collected.serializer()
}

/** One end of a connection, reading and writing whole lines. */
internal class Frames(channel: SocketChannel) {
  private val reader: BufferedReader = Channels.newInputStream(channel).bufferedReader(Charsets.UTF_8)
  private val writer: Writer = Channels.newOutputStream(channel).bufferedWriter(Charsets.UTF_8)

  fun send(request: Request) = line(wire.encodeToString(Request.serializer(), request))
  fun send(reply: Reply) = line(wire.encodeToString(Reply.serializer(), reply))

  /** Null once the other end has closed. Blocking: call it from an I/O thread. */
  fun request(): Request? = reader.readLine()?.let { wire.decodeFromString(Request.serializer(), it) }
  fun reply(): Reply? = reader.readLine()?.let { wire.decodeFromString(Reply.serializer(), it) }

  private fun line(text: String) {
    writer.write(text)
    writer.write("\n")
    writer.flush()
  }
}
