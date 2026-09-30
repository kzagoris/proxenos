package io.github.kzagoris.proxenos.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.provider.SetProperty
import org.gradle.language.base.plugins.LifecycleBasePlugin

/**
 * Declares what a module is allowed to see.
 *
 * Nothing is allowed by default, so a new module starts sealed and opens one edge at a time.
 */
abstract class ModuleBoundariesExtension {
  abstract val allowed: SetProperty<String>

  /**
   * Names a project this module may reach on its compile or runtime classpath. Transitive
   * arrivals count, so a module that pulls `core` in through someone else's `api` has to
   * say so here.
   */
  fun mayReach(vararg projectPaths: String) {
    allowed.addAll(*projectPaths)
  }
}

/**
 * Turns the modules' dependency directions from a convention into a build failure.
 *
 * The check runs on the main classpaths only. Test classpaths are deliberately left alone:
 * The control protocol is driven by the management client against an in-process
 * core, which means one test somewhere legitimately sees both sides. The guarantee this plugin
 * defends is about what the shipped module can reach.
 */
class ModuleBoundariesPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val boundaries =
      project.extensions.create("moduleBoundaries", ModuleBoundariesExtension::class.java)

    val check =
      project.tasks.register(TASK_NAME, CheckModuleBoundaries::class.java) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description =
          "Fails when this module reaches a project it has not declared with mayReach()."
        subject.set(project.path)
        allowed.set(boundaries.allowed)
        // The check reads a dependency graph, not files, so Gradle has nothing to
        // decide staleness from. Always running it costs a graph walk and keeps an
        // illegal dependency from arriving under an UP-TO-DATE.
        outputs.upToDateWhen { false }
      }

    project.plugins.withType(JavaPlugin::class.java) {
      CHECKED_CLASSPATHS.forEach { name ->
        check.configure {
          classpaths.put(
            name,
            project.configurations.named(name).flatMap {
              it.incoming.resolutionResult.rootComponent
            },
          )
        }
      }
      project.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) { dependsOn(check) }
    }
  }

  private companion object {
    const val TASK_NAME = "checkModuleBoundaries"
    val CHECKED_CLASSPATHS = listOf(
      JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME,
      JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME,
    )
  }
}
