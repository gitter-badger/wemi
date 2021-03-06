package wemi.dependency

import org.slf4j.LoggerFactory
import wemi.util.directorySynchronized
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Utility method to [resolveDependencies] dependencies and retrieve their [artifacts].
 *
 * If any dependency fails to resolve, returns null.
 */
fun resolveDependencyArtifacts(projects: Collection<Dependency>, repositories: Collection<Repository>): List<Path>? {
    val resolved = mutableMapOf<DependencyId, ResolvedDependency>()
    val ok = resolveDependencies(resolved, projects, repositories)

    if (!ok) {
        return null
    }

    return resolved.artifacts()
}

/**
 * Retrieve all artifacts from [ResolvedDependency].
 * Skips those without artifact. Does not check error status or anything else.
 */
fun Map<DependencyId, ResolvedDependency>.artifacts(): List<Path> {
    return mapNotNull { it.value.artifact?.path }
}

/**
 * Resolve [dependencies] and store what was resolved in [resolved].
 * Resolution is done using [repositories] and using [DependencyId.preferredRepository] and its cache, if any.
 * Actually resolved dependencies can be at any point modified with [mapper].
 *
 * This is the entry point to dependency resolution.
 *
 * @return true if all [dependencies] resolve correctly without error
 */
fun resolveDependencies(resolved: MutableMap<DependencyId, ResolvedDependency>, dependencies: Collection<Dependency>,
                        repositories: Collection<Repository>, mapper: ((Dependency) -> Dependency) = { it }): Boolean {
    // Sort repositories
    val sorted = ArrayList<Repository>(repositories)
    sorted.sortWith(REPOSITORY_COMPARATOR)

    // Lock repositories
    val directoriesToLock = HashSet<Path>()
    for (repository in repositories) {
        directoriesToLock.add(repository.cache?.directoryPath() ?: continue)
    }

    fun <Result> locked(iterator:Iterator<Path>, action:()->Result):Result {
        return if (!iterator.hasNext()) {
            action()
        } else {
            val directory = iterator.next()
            directorySynchronized(directory, {
                // On wait
                LOG.info("Waiting for lock on {}", directory)
            }) {
                locked(iterator, action)
            }
        }
    }

    return locked(directoriesToLock.iterator()) {
        val dependencyStack = ArrayList<DependencyId>()
        val exclusionStack = ArrayList<DependencyExclusion>()

        var ok = true
        for (project in dependencies) {
            if (!doResolveArtifacts(dependencyStack, exclusionStack, resolved, project, sorted, mapper)) {
                ok = false
            }
            assert(dependencyStack.isEmpty())
            assert(exclusionStack.isEmpty())
        }
        ok
    }
}

// -------------------------------- Internal ------------------------------

private val LOG = LoggerFactory.getLogger("LibraryDependencyResolver")
/** Repositories which are sorted for efficiency. Should be treated as opaque. */
internal typealias SortedRepositories = List<Repository>

/** Compares repositories so that local repositories are first. */
private val REPOSITORY_COMPARATOR = Comparator<Repository> { o1, o2 ->
    val cache1 = o1.cache
    val cache2 = o2.cache
    // Locals go first (cache == null <-> local)
    if (cache1 == null && cache2 != null) {
        -1
    } else if (cache1 != null && cache2 == null) {
         1
    } else {
        // Arbitrary sorting by name to get full ordering
        o1.name.compareTo(o2.name)
    }
}

/**
 * Resolve [dependency] using the [repositories] repository chain.
 * [DependencyId.preferredRepository] and its cache is tried first.
 *
 * Does not resolve transitively.
 * When resolution fails, returns ResolvedDependency with [ResolvedDependency.hasError] = true.
 */
internal fun resolveSingleDependency(dependencyId: DependencyId, repositories: SortedRepositories): ResolvedDependency {
    var log: StringBuilder? = null
    val startTime = System.nanoTime()

    LOG.debug("Resolving {}", dependencyId)

    fun resolveInRepository(repository: Repository?): ResolvedDependency? {
        if (repository == null) {
            return null
        }

        LOG.debug("Trying in {}", repository)
        val resolved = resolveInM2Repository(dependencyId, repository, repositories)
        if (!resolved.hasError) {
            LOG.debug("Resolution success {} ({} ms)", resolved, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime))
            return resolved
        } else {
            val sb = log ?: StringBuilder()
            if (sb.isEmpty()) {
                sb.append("tried: ").append(repository.name)
            } else {
                sb.append(", ").append(repository.name)
            }
            log = sb
        }

        return null
    }

    // Try preferred repository and its cache first
    val preferred = resolveInRepository(dependencyId.preferredRepository)
    if (preferred != null) {
        return preferred
    }

    // Try ordered repositories
    for (repository in repositories) {
        // Skip preferred repositories as those were already tried
        if (repository == dependencyId.preferredRepository
                || repository == dependencyId.preferredRepository?.cache) {
            continue
        }

        resolveInRepository(repository)?.let { return it }
    }

    // Fail
    LOG.debug("Failed to resolve {} ({} ms)", dependencyId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime))
    return ResolvedDependency(dependencyId, log ?: "no repositories to search in")
}

/**
 * Resolves artifacts for [dependency], using [resolveSingleDependency],
 * and artifacts for transitive dependencies by calling itself.
 *
 * Remembers errors, but does not stop on them and tries to resolve as much as possible.
 *
 * @param resolved cache for already resolved dependencies
 * @param repositories to use
 * @param mapper to modify which dependency is actually resolved
 * @return true if all dependencies resolved without error
 */
private fun doResolveArtifacts(dependencyStack:ArrayList<DependencyId>,
                               exclusionStack:ArrayList<DependencyExclusion>,
                               resolved: MutableMap<DependencyId, ResolvedDependency>,
                               dependency: Dependency, repositories: SortedRepositories,
                               mapper: (Dependency) -> Dependency): Boolean {

    val (dependencyId, exclusions) = mapper(dependency)

    val loopIndex = dependencyStack.indexOf(dependencyId)
    if (loopIndex != -1) {
        if (LOG.isInfoEnabled) {
            val arrow = " → "

            val sb = StringBuilder()
            for (i in 0 until loopIndex) {
                sb.append(dependencyStack[i]).append(arrow)
            }
            sb.append('↪').append(' ')
            for (i in loopIndex until dependencyStack.size) {
                sb.append(dependencyStack[i].toString()).append(arrow)
            }
            sb.setLength(Math.max(sb.length - arrow.length, 0))
            sb.append(' ').append('↩')

            LOG.info("Circular dependency: {}", sb)
        }
        return true
    }

    // Push
    dependencyStack.add(dependencyId)

    // Resolve
    var resolvedProject = resolved[dependencyId]
    if (resolvedProject == null
            || (resolvedProject.hasError
                    && resolvedProject.id.preferredRepository == null
                    && dependencyId.preferredRepository != null)) {
        // Either nothing is resolved, or we now know a different repository to look in,
        // so we might be more successful now. (Of course error is already in the result, so it won't count)
        resolvedProject = resolveSingleDependency(dependencyId, repositories)
        resolved[dependencyId] = resolvedProject
    }

    // Push
    exclusionStack.addAll(exclusions)

    var ok = !resolvedProject.hasError
    for (transitiveDependency in resolvedProject.dependencies) {
        val excluded = exclusionStack.any { rule ->
            if (rule.excludes(transitiveDependency.dependencyId)) {
                LOG.debug("Excluded {} with rule {} (dependency of {})", transitiveDependency.dependencyId, rule, dependencyId)
                true
            } else false
        }

        if (!excluded) {
            if (!doResolveArtifacts(dependencyStack, exclusionStack, resolved, transitiveDependency, repositories, mapper)) {
                ok = false
            }
        }
    }

    // Pop
    for (i in exclusions.indices) {
        exclusionStack.removeAt(exclusionStack.lastIndex)
    }
    dependencyStack.removeAt(dependencyStack.lastIndex)
    return ok
}
