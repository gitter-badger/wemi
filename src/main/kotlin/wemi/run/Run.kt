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

fun javaExecutable(javaHome:Path):Path {
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
        if (OSUtils.IS_WINDOWS) {
            return windowsFile
        } else {
            return unixFile
        }
    }
}

fun prepareJavaProcess(javaExecutable:Path, workingDirectory:Path, classpath:Collection<Path>,
                       mainClass:String, javaOptions:Collection<String>, args:Collection<String>):ProcessBuilder {
    val command = mutableListOf<String>()
    command.add(javaExecutable.absolutePath)
    command.add("-cp")
    command.add(classpath.joinToString(System.getProperty("path.separator", ":")))
    command.addAll(javaOptions)
    command.add(mainClass)
    command.addAll(args)

    LOG.debug("Starting command {} in {}", command, workingDirectory)
    return ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .inheritIO()
}