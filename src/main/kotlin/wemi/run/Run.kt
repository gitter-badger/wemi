package wemi.run

import org.jline.utils.OSUtils
import org.slf4j.LoggerFactory
import wemi.WemiException
import wemi.util.absolutePath
import wemi.util.div
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val LOG = LoggerFactory.getLogger("Run")

/**
 * Java home, as set by the java.home property.
 */
val JavaHome: Path = Paths.get(
        System.getProperty("java.home", null)
                ?: throw WemiException("java.home property is not set, can't find java executable")
).toAbsolutePath()

/**
 * Retrieve path to the java executable (starter of JVM), assuming that [javaHome] is the path to a valid JAVA_HOME.
 */
fun javaExecutable(javaHome: Path): Path {
    val windowsFile = (javaHome / "bin/java.exe").toAbsolutePath()
    val unixFile = (javaHome / "bin/java").toAbsolutePath()
    val winExists = Files.exists(windowsFile)
    val unixExists = Files.exists(unixFile)

    if (winExists && !unixExists) {
        return windowsFile
    } else if (!winExists && unixExists) {
        return unixFile
    } else if (!winExists && !unixExists) {
        if (OSUtils.IS_WINDOWS) {
            throw WemiException("Java executable should be at $windowsFile, but it does not exist")
        } else {
            throw WemiException("Java executable should be at $unixFile, but it does not exist")
        }
    } else {
        return if (OSUtils.IS_WINDOWS) {
            windowsFile
        } else {
            unixFile
        }
    }
}

/**
 * Prepare [ProcessBuilder] for java JVM launch.
 *
 * @param javaExecutable to be used
 * @param workingDirectory in which the process should run
 * @param classpath of the new JVM
 * @param mainClass to launch
 * @param javaOptions additional raw options for JVM
 * @param args for the launched program
 */
fun prepareJavaProcess(javaExecutable: Path, workingDirectory: Path, classpath: Collection<Path>,
                       mainClass: String, javaOptions: Collection<String>, args: Collection<String>): ProcessBuilder {
    val command = mutableListOf<String>()
    command.add(javaExecutable.absolutePath)
    command.add("-cp")
    command.add(classpath.joinToString(System.getProperty("path.separator", ":")))
    command.addAll(javaOptions)
    command.add(mainClass)
    command.addAll(args)

    LOG.debug("Prepared command {} in {}", command, workingDirectory)
    return ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
}