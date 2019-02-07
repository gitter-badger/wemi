package wemi.dependency

import com.esotericsoftware.jsonbeans.JsonException
import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import wemi.publish.InfoNode
import wemi.util.*
import wemi.util.Json
import java.net.URI
import java.net.URL
import java.nio.file.*
import java.security.MessageDigest

/**
 * Represents repository from which artifacts may be retrieved
 */
@Json(Repository.Serializer::class)
sealed class Repository(val name: String) {

    /** Local repositories are preferred, because retrieving from them is faster. */
    abstract val local: Boolean

    /** Repository acting as a cache for this repository. Always searched first.
     * Resolved dependencies will be stored here. */
    abstract val cache: Repository?

    /**
     * Attempt to resolve given dependency in this repository.
     *
     * @param dependency to resolve
     * @param chain of repositories which may be queried for looking up dependencies
     */
    internal abstract fun resolveInRepository(dependency: DependencyId, chain: RepositoryChain): ResolvedDependency

    /**
     * @return directory to lock on while resolving from this repository. It will be created if it doesn't exist.
     */
    internal abstract fun directoryToLock(): Path?

    /**
     * Publish [artifacts]s to this repository, under given [metadata]
     * and return the general location to where it was published.
     *
     * @param artifacts list of artifacts and their classifiers if any
     */
    internal abstract fun publish(metadata: InfoNode, artifacts: List<Pair<Path, String?>>):URI

    /** Maven repository.
     *
     * @param name of this repository, arbitrary (but should be consistent, as it is used for internal bookkeeping)
     * @param url of this repository
     * @param cache of this repository
     * @param checksum to use when retrieving artifacts from here
     * @param releases whether this repository should be used to query for release versions (non-SNAPSHOT)
     * @param snapshots whether this repository should be used to query for snapshot versions (versions ending with -SNAPSHOT)
     */
    @Json(Repository.Serializer::class)
    class M2(name: String, val url: URL, override val cache: M2? = null, val checksum: Checksum = M2.Checksum.SHA1, val releases:Boolean = true, val snapshots:Boolean = true) : Repository(name) {

        override val local: Boolean
            get() = "file".equals(url.protocol, ignoreCase = true)

        override fun resolveInRepository(dependency: DependencyId, chain: RepositoryChain): ResolvedDependency {
            return Maven2.resolveInM2Repository(dependency, this, chain)
        }

        override fun directoryToLock(): Path? {
            return directoryPath()
        }

        /**
         * @return Path to the directory root, if on this filesystem
         */
        private fun directoryPath(): Path? {
            try {
                if (local) {
                    return FileSystems.getDefault().getPath(url.path)
                }
            } catch (ignored:Exception) { }
            return null
        }

        override fun publish(metadata: InfoNode, artifacts: List<Pair<Path, String?>>): URI {
            val lock = directoryToLock()

            return if (lock != null) {
                directorySynchronized(lock) {
                    publishLocked(metadata, artifacts)
                }
            } else {
                publishLocked(metadata, artifacts)
            }
        }

        private fun Path.checkValidForPublish(snapshot:Boolean) {
            if (Files.exists(this)) {
                if (snapshot) {
                    LOG.info("Overwriting {}", this)
                } else {
                    throw UnsupportedOperationException("Can't overwrite published non-snapshot file $this")
                }
            } else {
                Files.createDirectories(this.parent)
            }
        }

        private fun publishLocked(metadata: InfoNode, artifacts: List<Pair<Path, String?>>):URI {
            val path = directoryPath() ?: throw UnsupportedOperationException("Can't publish to non-local repository")

            val groupId = metadata.findChild("groupId")?.text ?: throw IllegalArgumentException("Metadata is missing a groupId:\n$metadata")
            val artifactId = metadata.findChild("artifactId")?.text ?: throw IllegalArgumentException("Metadata is missing a artifactId:\n$metadata")
            val version = metadata.findChild("version")?.text ?: throw IllegalArgumentException("Metadata is missing a version:\n$metadata")

            val snapshot = version.endsWith("-SNAPSHOT")

            val pomPath = path / Maven2.pomPath(groupId, artifactId, version)
            LOG.debug("Publishing metadata to {}", pomPath)
            pomPath.checkValidForPublish(snapshot)
            val pomXML = metadata.toXML()
            Files.newBufferedWriter(pomPath, Charsets.UTF_8).use {
                it.append(pomXML)
            }
            // Create pom.xml hashes
            run {
                val pomXMLBytes = pomXML.toString().toByteArray(Charsets.UTF_8)
                for (checksum in PublishChecksums) {
                    val digest = checksum.digest()!!.digest(pomXMLBytes)

                    val publishedName = pomPath.name
                    val checksumFile = pomPath.parent.resolve("$publishedName${checksum.suffix}")
                    checksumFile.checkValidForPublish(snapshot)

                    checksumFile.writeText(createHashSum(digest, publishedName))
                    LOG.debug("Publishing metadata {} to {}", checksum, checksumFile)
                }
            }


            for ((artifact, classifier) in artifacts) {
                val publishedArtifact = path / Maven2.artifactPath(groupId, artifactId, version, classifier, artifact.name.pathExtension())
                LOG.debug("Publishing {} to {}", artifact, publishedArtifact)
                publishedArtifact.checkValidForPublish(snapshot)

                Files.copy(artifact, publishedArtifact, StandardCopyOption.REPLACE_EXISTING)
                // Create hashes
                val checksums = PublishChecksums
                val digests = Array(checksums.size) { checksums[it].digest()!! }

                Files.newInputStream(artifact).use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        for (digest in digests) {
                            digest.update(buffer, 0, read)
                        }
                    }
                }
                for (i in checksums.indices) {
                    val digest = digests[i].digest()

                    val publishedName = publishedArtifact.name
                    val checksumFile = publishedArtifact.parent.resolve("$publishedName${checksums[i].suffix}")
                    checksumFile.checkValidForPublish(snapshot)

                    checksumFile.writeText(createHashSum(digest, publishedName))
                    LOG.debug("Publishing {} {} to {}", publishedArtifact, checksum, checksumFile)
                }

                LOG.info("Published {} with {} checksum(s)", publishedArtifact, checksums.size)
            }

            return pomPath.parent.toUri()
        }

        companion object {
            private val LOG = LoggerFactory.getLogger(M2::class.java)

            /**
             * Various variants of the same dependency.
             * Examples: jdk15, sources, javadoc, linux
             */
            val Classifier = DependencyAttribute("m2-classifier", true)
            /**
             * Corresponds to the packaging of the dependency (and overrides it).
             * Determines what sort of artifact is retrieved.
             *
             * Examples: jar (default), pom (returns pom.xml, used internally)
             */
            val Type = DependencyAttribute("m2-type", true, "jar")
            /**
             * Scope of the dependency.
             *
             * Examples: compile, provided, test
             * See https://maven.apache.org/pom.html#Dependencies
             *
             * In Wemi used only when filtering.
             */
            val Scope = DependencyAttribute("m2-scope", false, "compile")
            /**
             * Optional dependencies are skipped by default by Wemi.
             */
            val Optional = DependencyAttribute("m2-optional", false, "false")

            /**
             * Concatenate two classifiers.
             */
            internal fun joinClassifiers(first:String?, second:String?):String? {
                return when {
                    first == null -> second
                    second == null -> first
                    else -> "$first-$second"
                }
            }

            /**
             * Classifier appended to artifacts with sources
             */
            const val SourcesClassifier = "sources"
            /**
             * Classifier appended to artifacts with Javadoc
             */
            const val JavadocClassifier = "javadoc"

            /**
             * [Checksum]s to generate when publishing an artifact.
             */
            val PublishChecksums = arrayOf(Checksum.MD5, Checksum.SHA1)
        }

        /**
         * Types of checksum in Maven repositories.
         *
         * @param suffix of files with this checksum (extension with dot)
         * @param algo Java digest algorithm name to use when computing this checksum
         */
        enum class Checksum(val suffix: String, private val algo: String) {
            /**
             * Special value for no checksum.
             *
             * Not recommended for general use - use only in extreme cases.
             */
            None(".no-checksum", "no-op"),
            // https://en.wikipedia.org/wiki/File_verification
            /**
             * Standard SHA1 algorithm with .md5 suffix.
             */
            MD5(".md5", "MD5"),
            /**
             * Standard SHA1 algorithm with .sha1 suffix.
             */
            SHA1(".sha1", "SHA-1");

            /**
             * Creates a [MessageDigest] for this [Checksum].
             * @return null if [None]
             * @throws java.security.NoSuchAlgorithmException if not installed
             */
            fun digest():MessageDigest? {
                if (this == None) {
                    return null
                }
                val digest = MessageDigest.getInstance(algo)
                digest.reset()
                return digest
            }

            fun checksum(data: ByteArray): ByteArray {
                val digest = digest() ?: return ByteArray(0)
                digest.update(data)
                return digest.digest()
            }
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("M2: ").append(name).append(" at ").append(url)
            if (cache != null) {
                sb.append(" (cached by ").append(cache.name).append(')')
            }
            return sb.toString()
        }
    }

    override fun toString(): String {
        return "Repository: $name"
    }

    internal class Serializer : wemi.util.JsonSerializer<Repository> {

        override fun JsonWriter.write(value: Repository) {
            // Exhaustive when
            return when (value) {
                is Repository.M2 -> {
                    writeObject {
                        field("type", "M2")
                        field("name", value.name)
                        field("url", value.url)

                        field("cache", value.cache)
                        field("checksum", value.checksum)
                    }
                }
            }
        }

        override fun read(value: JsonValue): Repository {
            val type = value.getString("type")
            when (type) {
                "M2" -> {
                    return Repository.M2(
                            value.field("name"),
                            value.field("url"),
                            value.field("cache"),
                            value.field("checksum"))
                }
                else -> {
                    throw JsonException("Unknown Repository type: $type")
                }
            }
        }
    }
}

/** Special collection of repositories in preferred order and with cache repositories inlined. */
typealias RepositoryChain = Collection<Repository>

/** Sorts repositories into an efficient chain.
 * Inlines cache repositories so that they are checked first.
 * Otherwise tries to maintain original order. */
fun createRepositoryChain(repositories: Collection<Repository>): RepositoryChain {
    val list = mutableListOf<Repository>()
    list.addAll(repositories)

    // Inline cache into the list
    for (repository in repositories) {
        list.add(repository.cache ?: continue)
    }

    // Sort to search local/cache first
    list.sortWith(Comparator { first, second ->
        if (first.local && !second.local) {
            -1
        } else if (!first.local && second.local) {
            1
        } else {
            0
        }
    })

    // Remove duplicates
    val seen = HashSet<Repository>()
    list.removeAll { repository ->
        val justAdded = seen.add(repository)
        !justAdded
    }
    return list
}

// Default repositories
/** Default local Maven repository stored in `~/.m2/repository`. Typically used as a local cache or for local releases. */
val LocalM2Repository = Repository.M2("local", (Paths.get(System.getProperty("user.home")) / ".m2/repository/").toUri().toURL(), null)

/** Maven Central repository at [maven.org](https://maven.org). Cached by [LocalM2Repository]. */
val MavenCentral = Repository.M2("central", URL("https://repo1.maven.org/maven2/"), LocalM2Repository, snapshots = false)

/** [Bintray JCenter repository](https://bintray.com/bintray/jcenter). Cached by [LocalM2Repository]. */
val JCenter = Repository.M2("jcenter", URL("https://jcenter.bintray.com/"), LocalM2Repository, snapshots = false)

/** [Jitpack repository](https://jitpack.io). Cached by [LocalM2Repository]. */
@Suppress("unused")
val Jitpack = Repository.M2("jitpack", URL("https://jitpack.io/"), LocalM2Repository)

/** [Sonatype Oss](https://oss.sonatype.org/) repository. Cached by [LocalM2Repository].
 * Most used [repository]-ies are `"releases"` and `"snapshots"`. */
@Suppress("unused")
fun sonatypeOss(repository:String):Repository.M2 {
    val releases:Boolean
    val snapshots:Boolean
    if (repository.contains("release", ignoreCase = true)) {
        releases = true
        snapshots = false
    } else if (repository.contains("snapshot", ignoreCase = true)) {
        releases = false
        snapshots = true
    } else {
        releases = true
        snapshots = true
    }

    return Repository.M2("sonatype-oss-$repository", URL("https://oss.sonatype.org/content/repositories/$repository/"), LocalM2Repository, releases = releases, snapshots = snapshots)
}

/**
 * Repositories to use by default.
 *
 * @see MavenCentral
 * @see LocalM2Repository (included as cache of [MavenCentral])
 */
val DefaultRepositories:Set<Repository> = setOf(MavenCentral)