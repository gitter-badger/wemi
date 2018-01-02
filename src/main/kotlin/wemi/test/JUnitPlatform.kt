package wemi.test

import com.esotericsoftware.jsonbeans.Json
import com.esotericsoftware.jsonbeans.JsonReader
import com.esotericsoftware.jsonbeans.OutputType
import org.slf4j.LoggerFactory
import wemi.Scope
import wemi.boot.CLI
import wemi.dependency.Dependency
import wemi.dependency.DependencyId
import wemi.dependency.MavenCentral
import wemi.test.TestStatus.*
import wemi.util.*
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.util.*

private val LOG = LoggerFactory.getLogger("JUnitPlatform")
private val TEST_OUTPUT_LOG = LoggerFactory.getLogger("TestOutput")

/**
 * Fully qualified class name of the file that contains.
 */
internal const val TEST_LAUNCHER_MAIN_CLASS = "wemi.test.forked.TestLauncherKt"

/**
 * Currently used JUnit Platform version
 */
val JUnitPlatformVersion = "1.0.2"

/**
 * Currently used JUnit Jupiter api/engine version
 */
val JUnitEngineVersion = "5.0.2"

/**
 * Dependency on JUnit 5 API
 *
 * @see [JUnitEngineVersion] for used version (based on Wemi version)
 */
@Suppress("unused")
val Scope.JUnitAPI: Dependency
    get() = Dependency(DependencyId("org.junit.jupiter", "junit-jupiter-api", JUnitEngineVersion, MavenCentral))

/**
 * Dependency on JUnit 5 Engine
 *
 * @see [JUnitEngineVersion] for used version (based on Wemi version)
 */
@Suppress("unused")
val Scope.JUnitEngine: Dependency
    get() = Dependency(DependencyId("org.junit.jupiter", "junit-jupiter-engine", JUnitEngineVersion, MavenCentral))

/**
 * DependencyId for the launcher needed to execute tests based on JUnit platform.
 */
internal val JUnitPlatformLauncher = DependencyId(
        "org.junit.platform",
        "junit-platform-launcher",
        JUnitPlatformVersion,
        preferredRepository = MavenCentral)

/**
 * Handle running the process that does testing.
 * In particular, this sends the process [testParameters], logs its output and receives and returns
 * created [TestReport].
 */
internal fun handleProcessForTesting(builder: ProcessBuilder, testParameters: TestParameters):TestReport? {
    builder.redirectErrorStream(false)
    builder.redirectError(ProcessBuilder.Redirect.PIPE)
    builder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    builder.redirectInput(ProcessBuilder.Redirect.PIPE)

    LOG.debug("Starting test process")
    val process = builder.start()

    val json = Json(OutputType.json)
    val testParametersJson = json.toJson(testParameters, TestParameters::class.java)

    OutputStreamWriter(process.outputStream, Charsets.UTF_8).use {
        it.write(testParametersJson)
        it.flush()
    }

    val stdout = ByteArrayOutputStream()
    val stderr = LineReadingOutputStream { line ->
        if (line.isNotBlank()) {
            TEST_OUTPUT_LOG.info("{}", line)
        }
    }

    val procStdout = process.inputStream
    val procStderr = process.errorStream

    while (true) {
        val alive = process.isAlive
        readFully(stdout, procStdout)
        readFully(stderr, procStderr)

        if (alive) {
            Thread.sleep(10)
        } else {
            stderr.close()
            break
        }
    }

    val status = process.exitValue()
    if (status == 0) {
        LOG.debug("Test process ended with status 0")
    } else {
        LOG.warn("Test process ended with status {}", status)
    }

    val stdoutString = stdout.toString(Charsets.UTF_8.name())
    LOG.trace("Test process returned stdout: {}", stdoutString)

    return try {
        val stdoutJson = JsonReader().parse(stdoutString)
        if (stdoutJson == null) {
            LOG.error("Failed to parse returned output:\n{}", stdoutString)
            return null
        }

        val report = TestReport()
        report.read(json, stdoutJson)
        LOG.debug("Test process returned report: {}", report)

        report
    } catch (e:Exception) {
        LOG.error("Malformed test report output:\n{}", stdoutString, e)
        null
    }
}

private val ICON_SUCCESS = CLI.format("✔", CLI.Color.Green)
private val ICON_SKIPPED = CLI.format("↷", CLI.Color.Magenta)
private val ICON_FAILED = CLI.format("✘", CLI.Color.Red)
private val ICON_ABORTED = CLI.format("■", CLI.Color.Yellow)
private val ICON_NOT_RUN = CLI.format("?", CLI.Color.Yellow)


fun TestReport.prettyPrint():CharSequence {
    val keys = keys.toMutableList()
    keys.sortBy { it.id }

    val roots = ArrayList<TreeNode<TestIdentifier>>()
    val stack = ArrayDeque<TreeNode<TestIdentifier>>()

    fun popUntilChild(identifier: TestIdentifier):TreeNode<TestIdentifier>? {
        if (stack.isEmpty()) {
            return null
        }

        while (true) {
            val top = stack.peekFirst()
            if (identifier.id.startsWith(top.value.id)) {
                // Top is a parent of identifier
                return top
            }

            // Top is not a parent, we must pop
            val popped = stack.removeFirst()
            if (stack.isEmpty()) {
                roots.add(popped)
                return null
            }
        }
    }

    // Build trees
    for (identifier in keys) {
        val parent = popUntilChild(identifier)
        if (parent == null) {
            // identifier is a root and has no parent
            stack.addFirst(TreeNode(identifier))
        } else {
            // identifier is a child of parent
            val node = TreeNode(identifier)
            parent.add(node)
            stack.addFirst(node)
        }
    }

    // Collect last tree
    if (stack.isNotEmpty()) {
        roots.add(stack.peekLast())
    }

    // Time to print
    val result = StringBuilder()
    val tree = printTree(roots) { sb ->
        val data = this@prettyPrint[this]!!

        // Name (+ test/container)
        var name:CharSequence = displayName
        if (name.isBlank()) {
            name = id
        }
        if (isContainer) {
            name = CLI.format(name, format = CLI.Format.Italic)
        }
        if (isTest) {
            name = CLI.format(name, format = CLI.Format.Bold)
        }
        sb.append(name)

        // Status
        sb.append(' ')
        when (data.status) {
            SUCCESSFUL -> sb.append(ICON_SUCCESS)
            ABORTED -> sb.append(ICON_ABORTED)
            SKIPPED -> sb.append(ICON_SKIPPED)
            FAILED -> sb.append(ICON_FAILED)
            NOT_RUN -> sb.append(ICON_NOT_RUN)
        }

        // Skip reason
        val skipReason = data.skipReason
        if (skipReason != null) {
            sb.append(' ')
            sb.append(CLI.format(skipReason, CLI.Color.White))
        }

        // Timing
        if (data.duration >= 0L) {
            sb.append(' ')
            sb.append(CLI.format(formatTimeDuration(data.duration), CLI.Color.Cyan, format = CLI.Format.Italic))
        }

        // Stack trace
        val stackTrace = data.stackTrace
        if (stackTrace != null) {
            sb.append('\n')
            sb.append(stackTrace)
        }

        // Reports
        var lastTimestamp = -1L
        for (report in data.reports) {
            sb.append('\n')
            if (report.timestamp != lastTimestamp) {
                lastTimestamp = report.timestamp
                val instant = Instant.ofEpochMilli(report.timestamp)
                val dateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
                sb.append(dateTime.toLocalDate()).append(' ').append(dateTime.toLocalTime()).append(':').append('\n')
            }
            sb.append(" ")
            sb.append(CLI.format(report.key, CLI.Color.White))
            sb.append(" = \"")
            sb.append(CLI.format(report.value, CLI.Color.Blue))
            sb.append('"')
        }
    }

    result.append(tree)
    // Do the status report

    var containersFound = 0
    var containersSkipped = 0
    var containersAborted = 0
    var containersSuccessful = 0
    var containersFailed = 0

    var testsFound = 0
    var testsSkipped = 0
    var testsAborted = 0
    var testsSuccessful = 0
    var testsFailed = 0

    for ((identifier, data) in entries) {
        if (identifier.isContainer) {
            containersFound++
            @Suppress("NON_EXHAUSTIVE_WHEN")
            when (data.status) {
                SUCCESSFUL -> containersSuccessful++
                ABORTED -> containersAborted++
                SKIPPED -> containersSkipped++
                FAILED -> containersFailed++
            }
        }

        if (identifier.isTest) {
            testsFound++
            @Suppress("NON_EXHAUSTIVE_WHEN")
            when (data.status) {
                SUCCESSFUL -> testsSuccessful++
                ABORTED -> testsAborted++
                SKIPPED -> testsSkipped++
                FAILED -> testsFailed++
            }
        }
    }

    result.append('\n')
    result.append(String.format(
                      "  %10d containers found\n"
                    + "  %10d containers skipped\n"
                    + "  %10d containers aborted\n"
                    + "  %10d containers successful\n"
                    + "  %10d containers failed\n\n"

                    + "  %10d tests found\n"
                    + "  %10d tests skipped\n"
                    + "  %10d tests aborted\n"
                    + "  %10d tests successful\n"
                    + "  %10d tests failed\n",
            containersFound,
            containersSkipped,
            containersAborted,
            containersSuccessful,
            containersFailed,

            testsFound,
            testsSkipped,
            testsAborted,
            testsSuccessful,
            testsFailed
    ))

    return result
}
