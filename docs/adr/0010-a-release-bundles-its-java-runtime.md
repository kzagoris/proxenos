# A release bundles its Java runtime, for linux-x64, beside the portable archive

The code is compiled for Java 26, which is not an LTS release and which few machines have. A
release therefore publishes two archives of the one distribution tree (SPEC §12):
`proxenos-<version>-linux-x64.tar.gz`, which adds a `jlink`ed runtime in `jre/`, and
`proxenos-<version>.tar.gz`, which carries none and runs on any architecture that has a Java 26.
The bundled launchers use `jre/` unconditionally, ignoring `JAVA_HOME`.

## Considered options

**Only the portable archive** is smaller and one artifact, but it moves the hardest prerequisite
onto the user: finding a Java 26, and learning what `class file version 70.0` means when the one
they have is older. Downloading a release instead of building should remove the toolchain, not
just Gradle.

**Only bundled archives, one per architecture**, would drop the Java prerequisite everywhere, at
the price of an arm64 build and smoke run per release. linux-x64 is the machine the project is
used on; the portable archive keeps every other architecture served with one file instead.

**A native image** (GraalVM) would drop the runtime altogether, but it reopens reflection and
native-library configuration for Ktor and Mosaic, and none of it is exercised by the tests the
JVM build runs.

**Honouring `JAVA_HOME` in the bundled launchers** was rejected: someone who downloads the
archive that promises to need no Java, and has an old `JAVA_HOME` exported, would get
`UnsupportedClassVersionError` instead of the runtime beside the launcher.

**Computing the module list with `jdeps`** over non-modular Kotlin jars is unreliable, so the
list is fixed in `build.gradle.kts`. It is kept honest by the release, which drives the unpacked
bundled archive with `tui/drive.py` under a `JAVA_HOME` that does not exist: a missing module
fails there, before anything is tagged.

## Consequences

A dependency that starts using a new JDK module must add it to the list; CI builds the bundled
archive on every commit and the release smoke test fails when it is missing. The bundled archive
is about 55 MB, the portable one about 17 MB. Moving the JDK pin moves the bundled runtime with
it, since `jlink` is taken from the same toolchain.
