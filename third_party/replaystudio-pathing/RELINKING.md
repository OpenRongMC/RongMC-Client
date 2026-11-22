# Rebuild and replace this LGPL library

The vendored Java source files are unchanged upstream files. The Gradle build rewrites only their package/import prefix into an isolated namespace, then compiles a separate Java 21 JAR.

Build with JDK 21 and Gradle 9.3.1: `gradle jar`. From this repository, the root wrapper can also be used with `../../gradlew -p . jar` after selecting JDK 21.

Fabric Loom adds a small fabric.mod.json when including this library in the RongMC mod. When replacing the library in an existing distribution, retain that generated fabric.mod.json and the nested library filename from META-INF/jars, and replace the library classes/resources with your rebuilt JAR. Alternatively rebuild RongMC with the modified library project to let Loom regenerate the nested metadata. No application class relocation or shader/video tool is needed.

The corresponding source archive includes all selected upstream sources, the namespace transformation/build scripts, provenance and license notices. COPYING.LESSER describes the applicable library modification and redistribution terms.
