package wemi.documentation

import java.nio.file.Path

/**
 * Contains all options needed for Dokka invocation through [DokkaInterface].
 *
 * @see wemi.Keys.archiveDokkaOptions
 */
class DokkaOptions {

    /**
     * Roots from which sources to be documented will be taken.
     */
    val sourceRoots: MutableList<SourceRoot> = ArrayList()

    /**
     * @param dir directory of the source root
     * @param platforms to which this source root belongs (see [impliedPlatforms]), empty for default
     */
    class SourceRoot(val dir: Path, val platforms:List<String> = emptyList()) {
        override fun toString(): String {
            return "$dir $platforms"
        }
    }

    /**
     * List of directories containing sample code (documentation for those directories is not generated,
     * but declarations from them can be referenced using the @sample tag).
     */
    val sampleRoots: MutableList<Path> = ArrayList()

    /**
     * List of '.md' files with package and module docs.
     * See [documentation](http://kotlinlang.org/docs/reference/kotlin-doc.html#module-and-package-documentation).
     */
    val includes: MutableList<Path> = ArrayList()

    /**
     * @see sourceLinks
     * @param dir Source directory of this project (<projectRoot>/src/main/kotlin)
     * @param url showing where the source code can be accessed on the web (http://github.com/me/myrepo)
     * @param urlSuffix which is used to append the line number to the URL. Use `#L` for GitHub
     */
    class SourceLinkMapItem(val dir: Path, val url: String, val urlSuffix:String? = null) {
        override fun toString(): String {
            return if (urlSuffix != null) {
                "$dir to $url (with suffix '$urlSuffix')"
            } else {
                "$dir to $url"
            }
        }
    }

    /**
     * Specifies the location of the project source code on the Web. If provided, Dokka generates "source" links
     * for each declaration.
     */
    val sourceLinks: MutableList<SourceLinkMapItem> = ArrayList()

    /**
     * The name of the module being documented (used as the root directory of the generated documentation)
     */
    var moduleName: String = ""

    /**
     * Documentation format.
     *
     * @see FORMAT_HTML and other FORMAT_* constants
     */
    var outputFormat:String = FORMAT_HTML

    /**
     * Used for linking to JDK
     */
    var jdkVersion: Int = 6

    /**
     * Do not output deprecated members, applies globally, can be overridden by packageOptions.
     */
    var skipDeprecated = false

    /**
     * Emit warnings about not documented members, applies globally, also can be overridden by packageOptions.
     */
    var reportNotDocumented = true

    /**
     * Do not create index pages for empty packages.
     */
    var skipEmptyPackages = true

    /**
     * See documentation about [platforms](https://github.com/Kotlin/dokka#platforms).
     */
    val impliedPlatforms: MutableList<String> = ArrayList()

    /**
     * @see [perPackageOptions]
     * @param prefix to match the package (`"kotlin"` will match `kotlin` and all sub-packages of it)
     * @param skipDeprecated see [DokkaOptions.skipDeprecated]
     * @param reportNotDocumented see [DokkaOptions.reportNotDocumented]
     * @param includeNonPublic
     */
    class PackageOptions(
            val prefix:String,
            val skipDeprecated:Boolean = false,
            val reportNotDocumented:Boolean = true,
            val includeNonPublic:Boolean = false) {
        override fun toString(): String {
            return "$prefix (skipDeprecated=$skipDeprecated, reportNotDocumented=$reportNotDocumented, includeNonPublic=$includeNonPublic)"
        }
    }

    /**
     * Allows to customize documentation generation options on a per-package basis.
     */
    val perPackageOptions: MutableList<PackageOptions> = ArrayList()

    /**
     * @see externalDocumentationLinks
     * @param url Root URL of the generated documentation to link with. The trailing slash is required! (https://example.com/docs/)
     * @param packageListUrl If package-list file located in non-standard location (file:///home/user/localdocs/package-list)
     */
    class ExternalDocumentation(val url:String, val packageListUrl:String? = null) {
        override fun toString(): String {
            return if (packageListUrl != null) {
                url
            } else {
                "$url (packageList at $packageListUrl)"
            }
        }
    }

    /**
     * Allows linking to documentation of the project's dependencies (generated with Javadoc or Dokka).
     */
    val externalDocumentationLinks: ArrayList<ExternalDocumentation> = ArrayList()

    /**
     * No default documentation link to kotlin-stdlib.
     */
    var noStdlibLink: Boolean = false

    @Suppress("unused")
    companion object {
        /**
         * Minimalistic html format used by default
         */
        const val FORMAT_HTML = "html"

        /**
         * Dokka mimic to javadoc
         */
        const val FORMAT_JAVADOC = "javadoc"

        /**
         * As html but using java syntax
         */
        const val FORMAT_HTML_AS_JAVA = "html-as-java"

        /**
         * Markdown structured as html
         */
        const val FORMAT_MARKDOWN = "markdown"

        /**
         * GitHub flavored markdown
         */
        const val FORMAT_GFM = "gfm"

        /**
         * Jekyll compatible markdown
         */
        const val FORMAT_JEKYLL = "jekyll"
    }

    override fun toString(): String {
        return "sourceRoots=$sourceRoots\nsampleRoots=$sampleRoots\nincludes=$includes\nsourceLinks=$sourceLinks\nmoduleName='$moduleName'\noutputFormat='$outputFormat'\njdkVersion=$jdkVersion\nskipDeprecated=$skipDeprecated\nreportNotDocumented=$reportNotDocumented\nskipEmptyPackages=$skipEmptyPackages\nimpliedPlatforms=$impliedPlatforms\nperPackageOptions=$perPackageOptions\nexternalDocumentationLinks=$externalDocumentationLinks\nnoStdlibLink=$noStdlibLink"
    }
}