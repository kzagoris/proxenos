package io.github.kzagoris.proxenos.core

import io.github.kzagoris.proxenos.coreapi.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.serializer

/** Output shapes follow the wire types; the adapter renders, but never invents, this contract. */
internal fun outputContract(name: String): ArgumentSpec {
  val result = when (name) {
    "list_workspaces" -> shape<List<WorkspaceListing>>()
    "read_file" -> shape<FileContent>()
    "read_files" -> shape<BatchRead>()
    "list_directory" -> shape<DirectoryListing>()
    "search" -> shape<SearchResults>()
    "git_status" -> shape<GitStatusReport>()
    "git_diff" -> shape<GitDiffReport>()
    "git_log" -> shape<GitLogReport>()
    "write_file", "edit_file" -> shape<FileWritten>()
    "create_directory" -> shape<DirectoryCreated>()
    "run_command" -> shape<CommandReply>()
    "get_result" -> shape<Collected>()
    else -> error("No output contract for catalog entry $name")
  }
  return ArgumentSpec("", ArgumentType.Object, true, "Operation reply, including refusals and malformed arguments.", properties = listOf(
    ArgumentSpec("status", ArgumentType.Text, true, "Promoted/running replies precede an outcome.",
      enumValues = listOf("ok", "failed", "uncertain", "malformed", "promoted", "running", "reached", "recorded")),
    ArgumentSpec("recorded", ArgumentType.Flag, true, "A recorded Delivery answer; no second execution."),
    result.copy(name = "result", required = false, definitions = emptyList()),
    ArgumentSpec("message", ArgumentType.Text, false, "Readable warning, preserved verbatim."),
    shape<List<EditConflict>>().copy(name = "conflicts", required = false),
    shape<List<String>>().copy(name = "createdPaths", required = false),
    ArgumentSpec("incomplete", ArgumentType.Flag, true, "The transport budget omitted output; narrow the Operation when true."),
  ), definitions = result.definitions)
}

private inline fun <reified T> shape(): ArgumentSpec {
  val context = Shapes()
  return context.shape(serializer<T>().descriptor).copy(definitions = context.definitions.values.toList())
}

@OptIn(ExperimentalSerializationApi::class)
private class Shapes {
  val definitions = linkedMapOf<String, ArgumentSpec>()
  private val visiting = mutableSetOf<String>()
  private val referenced = mutableSetOf<String>()

  fun shape(descriptor: SerialDescriptor): ArgumentSpec {
    val name = descriptor.serialName
    if (!visiting.add(name)) {
      referenced += name
      return ArgumentSpec("", ArgumentType.Object, true, "", reference = "#/$" + "defs/" + name.replace("~", "~0").replace("/", "~1"))
    }
    fun base(type: ArgumentType) = ArgumentSpec("", type, true, "", nullable = descriptor.isNullable)
    val result = when (descriptor.kind) {
      PrimitiveKind.BOOLEAN -> base(ArgumentType.Flag)
      PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> base(ArgumentType.Integer)
      PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> base(ArgumentType.Number)
      PrimitiveKind.CHAR, PrimitiveKind.STRING -> base(ArgumentType.Text)
      StructureKind.LIST -> base(ArgumentType.Array).copy(items = shape(descriptor.getElementDescriptor(0)))
      SerialKind.ENUM -> base(ArgumentType.Text).copy(enumValues = (0 until descriptor.elementsCount).map(descriptor::getElementName))
      PolymorphicKind.SEALED -> {
        val values = descriptor.getElementDescriptor(1)
        base(ArgumentType.Object).copy(alternatives = (0 until values.elementsCount).map { index ->
          val child = values.getElementDescriptor(index)
          val fields = shape(child)
          fields.copy(properties = listOf(ArgumentSpec("type", ArgumentType.Text, true, "", enumValues = listOf(child.serialName))) + fields.properties)
        })
      }
      else -> base(ArgumentType.Object).copy(properties = (0 until descriptor.elementsCount).map { index ->
        shape(descriptor.getElementDescriptor(index)).copy(name = descriptor.getElementName(index), required = !descriptor.isElementOptional(index))
      })
    }
    visiting.remove(name)
    if (name in referenced) definitions[name] = result.copy(name = name)
    return result
  }
}
