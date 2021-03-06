package wemi

import org.jline.reader.EndOfFileException
import org.jline.reader.UserInterruptException
import org.slf4j.LoggerFactory
import wemi.boot.CLI
import wemi.boot.WemiRunningInInteractiveMode
import wemi.util.*
import wemi.util.CliStatusDisplay.Companion.withStatus

private val LOG = LoggerFactory.getLogger("Input")

@PublishedApi
internal val NO_INPUT = emptyArray<Pair<String,String>>()

/**
 * Convenience method, calls [read] with [StringValidator].
 */
fun EvalScope.read(key: String, description: String): String? = read(key, description, StringValidator)

/**
 * Read a [V] from the input.
 * The value is first searched for using the [key] from explicit input pairs.
 * Then, free input strings (without explicit [key]s) are considered. Both are considered from top
 * (added last) to bottom, and only if they are accepted by the [validator].
 * As a last resort, user is asked, if in interactive mode. Otherwise, the query fails.
 *
 * @param key simple, non-user-readable key (case insensitive, converted to lowercase)
 * @param description displayed to the user, if asked interactively
 * @param validator to use for validation and conversion of found string
 * @param doNotAsk if value is not already specified, do not ask the user and return null
 * @return found value or null if validator fails on all possible values
 */
fun <V> EvalScope.read(key: String, description: String, validator: Validator<V>, doNotAsk:Boolean = false): V? {
    val input = this.input

    // Search in prepared by key
    for ((preparedKey, preparedValue) in input) {
        if (!preparedKey.equals(key, ignoreCase = true)) {
            continue
        }

        validator(preparedValue).use<Unit>({
            SimpleHistory.getHistory(SimpleHistory.inputHistoryName(key)).add(preparedValue)
            return it
        }, {
            LOG.info("Can't use '{}' for input key '{}': {}", preparedValue, key, it)
        })
    }

    // Search in prepared for free
    // Move nextFreeInput to a valid index of free input
    while (nextFreeInput < input.size && input[nextFreeInput].first.isNotEmpty()) {
        nextFreeInput++
    }
    // Try to use it
    if (nextFreeInput < input.size) {
        val freeInput = input[nextFreeInput].second
        validator(freeInput).use({
            // We will use this free input
            SimpleHistory.getHistory(SimpleHistory.inputHistoryName(key)).add(freeInput)
            SimpleHistory.getHistory(SimpleHistory.inputHistoryName(null)).add(freeInput)
            nextFreeInput++
            return it
        }, {
            LOG.info("Can't use free '{}' for input key '{}': {}", freeInput, key, it)
        })
    }

    if (doNotAsk) {
        return null
    }

    // Still no hit, read interactively
    if (!WemiRunningInInteractiveMode) {
        LOG.info("Not asking for {} - '{}', not interactive", key, description)
        return null
    }

    try {
        while (true) {
            val line = CLI.InputLineReader.run {
                val previousHistory = history
                try {
                    history = SimpleHistory.getHistory(SimpleHistory.inputHistoryName(key))
                    CLI.MessageDisplay.withStatus(false) {
                        readLine("${format(description, format = Format.Bold)} (${format(key, Color.White)}): ")
                    }
                } finally {
                    history = previousHistory
                }
            }
            val value = validator(line)
            value.use({
                return it
            }, {
                print(format("Invalid input: ", format = Format.Bold))
                println(format(it, foreground = Color.Red))
            })
        }
    } catch (e: UserInterruptException) {
        return null
    } catch (e: EndOfFileException) {
        return null
    }
}


/**
 * A function that validates given string input and converts it to the desirable type.
 * Should be pure. Returns [Failable], with the validated/converted value or with an error string,
 * that will be printed for the user verbatim.
 */
typealias Validator<V> = (String) -> Failable<V, String>

/**
 * Default validator, always succeeds and returns the entered string, no matter what it is.
 */
val StringValidator: Validator<String> = { Failable.success(it) }

/**
 * Integer validator, accepts decimal numbers
 */
@Suppress("unused")
val IntValidator: Validator<Int> = { Failable.failNull(it.trim().toIntOrNull(), "Integer expected") }

/**
 * Boolean validator, treats true, yes, 1 and y as true, false, no, 0 and n as false.
 */
@Suppress("unused")
val BooleanValidator: Validator<Boolean> = {
    when (it.toLowerCase()) {
        "true", "yes", "1", "y", "on" ->
            Failable.success(true)
        "false", "no", "0", "n", "off" ->
            Failable.success(false)
        else ->
            Failable.failure("Boolean expected")
    }
}

/**
 * String validator, succeeds only if string is a valid class name with package.
 * (For example "wemi.Input")
 */
val ClassNameValidator: Validator<String> = validator@ {
    val className = it.trim()

    var firstLetter = true
    for (c in className) {
        if (firstLetter) {
            if (!c.isJavaIdentifierStart()) {
                return@validator Failable.failure("Invalid character '$c' - class name expected")
            }
            firstLetter = false
        } else {
            if (!c.isJavaIdentifierPart()) {
                if (c == '.') {
                    firstLetter = true
                } else {
                    return@validator Failable.failure("Invalid character '$c' - class name expected")
                }
            }
        }
    }
    if (firstLetter) {
        return@validator Failable.failure("Class name is incomplete")
    }

    Failable.success(className)
}
