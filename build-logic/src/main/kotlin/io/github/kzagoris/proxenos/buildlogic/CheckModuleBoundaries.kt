package io.github.kzagoris.proxenos.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class CheckModuleBoundaries : DefaultTask() {
  @get:Input
  abstract val subject: Property<String>

  @get:Input
  abstract val allowed: SetProperty<String>

  /** Resolved dependency graphs, by configuration name, of the classpaths being policed. */
  @get:Internal
  abstract val classpaths: MapProperty<String, ResolvedComponentResult>

  @TaskAction
  fun check() {
    val path = subject.get()
    val graphs = classpaths.get()
    // A guardrail that silently checks nothing is worse than no guardrail: it reads green.
    if (graphs.isEmpty()) {
      throw GradleException(
        "$path has no classpath to check. moduleBoundaries needs a JVM plugin applied to the " +
          "module, which is what creates compileClasspath and runtimeClasspath.",
      )
    }

    val reached = graphs.values.flatMapTo(mutableSetOf(), ::projectsReachedFrom)
    val allowedPaths = allowed.get()

    val violations = violations(path, reached, allowedPaths)
    if (violations.isNotEmpty()) {
      throw GradleException(boundaryReport(path, violations, reached, allowedPaths))
    }
  }
}

/** Every project on this graph, however deep — the root itself included. */
private fun projectsReachedFrom(root: ResolvedComponentResult): Set<String> {
  val visited = mutableSetOf<ResolvedComponentResult>()
  val found = mutableSetOf<String>()

  fun visit(component: ResolvedComponentResult) {
    if (!visited.add(component)) return
    (component.id as? ProjectComponentIdentifier)?.let { found.add(it.projectPath) }
    component.dependencies
      .filterIsInstance<ResolvedDependencyResult>()
      .forEach { visit(it.selected) }
  }

  visit(root)
  return found
}
