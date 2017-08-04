package wemi.run

import org.jline.utils.OSUtils
import wemi.WemiException
import wemi.util.div
import java.io.File

/**
 *
 */

val JavaHome:File = File(
        System.getProperty("java.home", null)
        ?: throw WemiException("java.home property is not set, can't find java executable")
).absoluteFile

fun javaExecutable(javaHome:File):File = run {
    val windowsFile = (javaHome / "bin/java.exe").absoluteFile
    val unixFile = (javaHome / "bin/java").absoluteFile
    val winExists = windowsFile.exists()
    val unixExists = unixFile.exists()

    if (winExists && !unixExists) {
        windowsFile
    } else if (!winExists && unixExists) {
        unixFile
    } else if (!winExists && !unixExists) {
        throw WemiException("Java executables should be at $windowsFile or $unixFile, but neither exists")
    } else {
        if (OSUtils.IS_WINDOWS) {
            windowsFile
        } else {
            unixFile
        }
    }
}

fun runJava(javaExecutable:File, workingDirectory:File, classpath:Collection<File>, mainClass:String, javaOptions:Collection<String>, args:Collection<String>):Process {
    val command = mutableListOf<String>()
    command.add(javaExecutable.absolutePath)
    command.add("-cp")
    command.add(classpath.joinToString(System.getProperty("path.separator", ":")))
    command.addAll(javaOptions)
    command.add(mainClass)
    command.addAll(args)
    return ProcessBuilder(command)
            .directory(workingDirectory)
            .inheritIO()
            .start()
}