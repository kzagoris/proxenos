import io.github.kzagoris.proxenos.buildlogic.GenerateThirdPartyNotices
import io.github.kzagoris.proxenos.buildlogic.WriteRuntimeClasspathNotice

// Each application reports the artifacts Gradle actually resolved for its runtime classpath.
val noticeModules = setOf("runtime", "tui", "gui")
subprojects {
  if (name in noticeModules) {
    plugins.withId("org.jetbrains.kotlin.jvm") {
      val configuration = configurations.getByName("runtimeClasspath")
      val resolved = configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.mapNotNull { artifact ->
          val id = artifact.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
          id?.let { it.group + ":" + it.module + ":" + it.version }
        }.distinct()
      }
      tasks.register<WriteRuntimeClasspathNotice>("writeRuntimeClasspathNotice") {
        coordinates.set(resolved)
        moduleName.set(project.name)
        report.set(layout.buildDirectory.file("notices/runtime-classpath.txt"))
      }
    }
  }
}

val thirdPartyNotices = tasks.register<GenerateThirdPartyNotices>("generateThirdPartyNotices") {
  dependsOn(noticeModules.map { ":$it:writeRuntimeClasspathNotice" })
  reports.from(noticeModules.map { file("$it/build/notices/runtime-classpath.txt") })
  licenseDir.set(layout.projectDirectory.dir("docs/licenses"))
  output.set(layout.buildDirectory.file("generated/docs/THIRD-PARTY.md"))
}

