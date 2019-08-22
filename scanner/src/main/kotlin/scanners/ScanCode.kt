/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.scanner.scanners

import com.fasterxml.jackson.databind.JsonNode

import com.here.ort.model.CopyrightFinding
import com.here.ort.model.CopyrightFindings
import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.LicenseFinding
import com.here.ort.model.LicenseFindings
import com.here.ort.model.OrtIssue
import com.here.ort.model.Provenance
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.TextLocation
import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.model.jsonMapper
import com.here.ort.scanner.AbstractScannerFactory
import com.here.ort.scanner.HTTP_CACHE_PATH
import com.here.ort.scanner.LocalScanner
import com.here.ort.scanner.ScanException
import com.here.ort.scanner.ScanResultsStorage
import com.here.ort.spdx.LICENSE_FILE_MATCHERS
import com.here.ort.spdx.NON_LICENSE_FILENAMES
import com.here.ort.utils.CommandLineTool
import com.here.ort.utils.ORT_CONFIG_FILENAME
import com.here.ort.utils.Os
import com.here.ort.utils.OkHttpClientHelper
import com.here.ort.utils.ProcessCapture
import com.here.ort.utils.log
import com.here.ort.utils.unpack

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.time.Instant
import java.util.SortedMap
import java.util.SortedSet
import java.util.regex.Pattern

import kotlin.math.absoluteValue
import kotlin.math.max

import okhttp3.Request

import okio.buffer
import okio.sink

/**
 * A wrapper for [ScanCode](https://github.com/nexB/scancode-toolkit).
 *
 * This scanner can be configured in [ScannerConfiguration.scanner] using the key "ScanCode". It offers the following
 * configuration options:
 *
 * * **"commandLine":** Command line options that modify the result. These are added to the [ScannerDetails] when
 *   looking up results from the [ScanResultsStorage]. Defaults to [DEFAULT_CONFIGURATION_OPTIONS].
 * * **"commandLineNonConfig":** Command line options that do not modify the result and should therefore not be
 *   considered in [getConfiguration], like "--processes". Defaults to [DEFAULT_NON_CONFIGURATION_OPTIONS].
 * * **"debugCommandLine":** Debug command line options that modify the result. Only used if the [log] level is set to
 *   [Level.DEBUG]. Defaults to [DEFAULT_DEBUG_CONFIGURATION_OPTIONS].
 * * **"debugCommandLineNonConfig":** Debug command line options that do not modify the result and should therefore not
 *   be considered in [getConfiguration]. Only used if the [log] level is set to [Level.DEBUG]. Defaults to
 *   [DEFAULT_DEBUG_NON_CONFIGURATION_OPTIONS].
 */
class ScanCode(name: String, config: ScannerConfiguration) : LocalScanner(name, config) {
    class Factory : AbstractScannerFactory<ScanCode>("ScanCode") {
        override fun create(config: ScannerConfiguration) = ScanCode(scannerName, config)
    }

    companion object {
        private const val OUTPUT_FORMAT = "json-pp"
        private const val TIMEOUT = 300

        /**
         * Configuration options that are relevant for [getConfiguration] because they change the result file.
         */
        private val DEFAULT_CONFIGURATION_OPTIONS = listOf(
            "--copyright",
            "--license",
            "--ignore", "*$ORT_CONFIG_FILENAME",
            "--info",
            "--strip-root",
            "--timeout", TIMEOUT.toString()
        ) + NON_LICENSE_FILENAMES.flatMap { listOf("--ignore", it) }

        /**
         * Configuration options that are not relevant for [getConfiguration] because they do not change the result
         * file.
         */
        private val DEFAULT_NON_CONFIGURATION_OPTIONS = listOf(
            "--processes", max(1, Runtime.getRuntime().availableProcessors() - 1).toString()
        )

        /**
         * Debug configuration options that are relevant for [getConfiguration] because they change the result file.
         */
        private val DEFAULT_DEBUG_CONFIGURATION_OPTIONS = listOf("--license-diag")

        /**
         * Debug configuration options that are not relevant for [getConfiguration] because they do not change the
         * result file.
         */
        private val DEFAULT_DEBUG_NON_CONFIGURATION_OPTIONS = listOf("--verbose")

        private val OUTPUT_FORMAT_OPTION = if (OUTPUT_FORMAT.startsWith("json")) {
            "--$OUTPUT_FORMAT"
        } else {
            "--output-$OUTPUT_FORMAT"
        }

        // Note: The "(File: ...)" part in the patterns below is actually added by our own getRawResult() function.
        private val UNKNOWN_ERROR_REGEX = Pattern.compile(
            "(ERROR: for scanner: (?<scanner>\\w+):\n)?" +
                    "ERROR: Unknown error:\n.+\n(?<error>\\w+Error)(:|\n)(?<message>.*) \\(File: (?<file>.+)\\)",
            Pattern.DOTALL
        )

        private val TIMEOUT_ERROR_REGEX = Pattern.compile(
            "(ERROR: for scanner: (?<scanner>\\w+):\n)?" +
                    "ERROR: Processing interrupted: timeout after (?<timeout>\\d+) seconds. \\(File: (?<file>.+)\\)"
        )

        /**
         * Map messages about unknown errors to a more compact form. Return true if solely memory errors occurred,
         * return false otherwise.
         */
        internal fun mapUnknownErrors(errors: MutableList<OrtIssue>): Boolean {
            if (errors.isEmpty()) {
                return false
            }

            var onlyMemoryErrors = true

            val mappedErrors = errors.map { fullError ->
                UNKNOWN_ERROR_REGEX.matcher(fullError.message).let { matcher ->
                    if (matcher.matches()) {
                        val file = matcher.group("file")
                        val error = matcher.group("error")
                        if (error == "MemoryError") {
                            fullError.copy(message = "ERROR: MemoryError while scanning file '$file'.")
                        } else {
                            onlyMemoryErrors = false
                            val message = matcher.group("message").trim()
                            fullError.copy(message = "ERROR: $error while scanning file '$file' ($message).")
                        }
                    } else {
                        onlyMemoryErrors = false
                        fullError
                    }
                }
            }

            errors.clear()
            errors += mappedErrors.distinctBy { it.message }

            return onlyMemoryErrors
        }

        /**
         * Map messages about timeout errors to a more compact form. Return true if solely timeout errors occurred,
         * return false otherwise.
         */
        internal fun mapTimeoutErrors(errors: MutableList<OrtIssue>): Boolean {
            if (errors.isEmpty()) {
                return false
            }

            var onlyTimeoutErrors = true

            val mappedErrors = errors.map { fullError ->
                TIMEOUT_ERROR_REGEX.matcher(fullError.message).let { matcher ->
                    if (matcher.matches() && matcher.group("timeout") == TIMEOUT.toString()) {
                        val file = matcher.group("file")
                        fullError.copy(message = "ERROR: Timeout after $TIMEOUT seconds while scanning file '$file'.")
                    } else {
                        onlyTimeoutErrors = false
                        fullError
                    }
                }
            }

            errors.clear()
            errors += mappedErrors.distinctBy { it.message }

            return onlyTimeoutErrors
        }
    }

    override val scannerVersion = "3.0.2"
    override val resultFileExt = "json"

    private val scanCodeConfiguration = config.scanner?.get("ScanCode") ?: emptyMap()

    private val configurationOptions = scanCodeConfiguration["commandLine"]?.split(" ")
        ?: DEFAULT_CONFIGURATION_OPTIONS
    private val nonConfigurationOptions = scanCodeConfiguration["commandLineNonConfig"]?.split(" ")
        ?: DEFAULT_NON_CONFIGURATION_OPTIONS
    private val debugConfigurationOptions = scanCodeConfiguration["debugCommandLine"]?.split(" ")
        ?: DEFAULT_DEBUG_CONFIGURATION_OPTIONS
    private val debugNonConfigurationOptions = scanCodeConfiguration["debugCommandLineNonConfig"]?.split(" ")
        ?: DEFAULT_DEBUG_NON_CONFIGURATION_OPTIONS

    val commandLineOptions by lazy {
        mutableListOf<String>().apply {
            addAll(configurationOptions)
            addAll(nonConfigurationOptions)

            if (log.delegate.isDebugEnabled) {
                addAll(debugConfigurationOptions)
                addAll(debugNonConfigurationOptions)
            }
        }.toList()
    }

    override fun command(workingDir: File?) = if (Os.isWindows) "scancode.bat" else "scancode"

    override fun getVersion(dir: File): String {
        // Create a temporary tool to get its version from the installation in a specific directory.
        val cmd = command()
        val tool = object : CommandLineTool {
            override fun command(workingDir: File?) = dir.resolve(cmd).absolutePath
        }

        return tool.getVersion(transform = {
            // "scancode --version" returns a string like "ScanCode version 2.0.1.post1.fb67a181", so simply remove
            // the prefix.
            it.substringAfter("ScanCode version ")
        })
    }

    override fun bootstrap(): File {
        val archive = when {
            // Use the .zip file despite it being slightly larger than the .tar.gz file here as the latter for some
            // reason does not complete to unpack on Windows.
            Os.isWindows -> "v$scannerVersion.zip"
            else -> "v$scannerVersion.tar.gz"
        }

        // Use the source code archive instead of the release artifact from S3 to enable OkHttp to cache the download
        // locally. For details see https://github.com/square/okhttp/issues/4355#issuecomment-435679393.
        val url = "https://github.com/nexB/scancode-toolkit/archive/$archive"

        log.info { "Downloading $scannerName from $url... " }

        val request = Request.Builder().get().url(url).build()

        return OkHttpClientHelper.execute(HTTP_CACHE_PATH, request).use { response ->
            val body = response.body

            if (response.code != HttpURLConnection.HTTP_OK || body == null) {
                throw IOException("Failed to download $scannerName from $url.")
            }

            if (response.cacheResponse != null) {
                log.info { "Retrieved $scannerName from local cache." }
            }

            val scannerArchive = createTempFile("ort", "$scannerName-${url.substringAfterLast("/")}")
            scannerArchive.sink().buffer().use { it.writeAll(body.source()) }

            val unpackDir = createTempDir("ort", "$scannerName-$scannerVersion").apply { deleteOnExit() }

            log.info { "Unpacking '$scannerArchive' to '$unpackDir'... " }
            scannerArchive.unpack(unpackDir)
            if (!scannerArchive.delete()) {
                log.warn { "Unable to delete temporary file '$scannerArchive'." }
            }

            val scannerDir = unpackDir.resolve("scancode-toolkit-$scannerVersion")

            scannerDir
        }
    }

    override fun getConfiguration() =
        configurationOptions.toMutableList().run {
            add(OUTPUT_FORMAT_OPTION)
            if (log.delegate.isDebugEnabled) {
                addAll(debugConfigurationOptions)
            }
            joinToString(" ")
        }

    override fun scanPath(path: File, resultsFile: File): ScanResult {
        val startTime = Instant.now()

        val process = ProcessCapture(
            scannerPath.absolutePath,
            *commandLineOptions.toTypedArray(),
            path.absolutePath,
            OUTPUT_FORMAT_OPTION,
            resultsFile.absolutePath
        )

        val endTime = Instant.now()

        if (process.stderr.isNotBlank()) {
            log.debug { process.stderr }
        }

        val result = getRawResult(resultsFile)
        val summary = generateSummary(startTime, endTime, result)

        val errors = summary.errors.toMutableList()

        val hasOnlyMemoryErrors = mapUnknownErrors(errors)
        val hasOnlyTimeoutErrors = mapTimeoutErrors(errors)

        with(process) {
            if (isSuccess || hasOnlyMemoryErrors || hasOnlyTimeoutErrors) {
                return ScanResult(Provenance(), getDetails(), summary.copy(errors = errors), result)
            } else {
                throw ScanException(errorMessage)
            }
        }
    }

    override fun getRawResult(resultsFile: File) =
        if (resultsFile.isFile && resultsFile.length() > 0L) {
            jsonMapper.readTree(resultsFile)
        } else {
            EMPTY_JSON_NODE
        }

    private fun getFileCount(result: JsonNode): Int {
        // Handling for ScanCode 2.9.8 and above.
        result["headers"]?.forEach { header ->
            header["extra_data"]?.get("files_count")?.let {
                return it.intValue()
            }
        }

        // Handling for ScanCode 2.9.7 and below.
        return result["files_count"].intValue()
    }

    override fun generateSummary(startTime: Instant, endTime: Instant, result: JsonNode): ScanSummary {
        val fileCount = getFileCount(result)
        val findings = associateFindings(result)
        val errors = mutableListOf<OrtIssue>()

        result["files"]?.forEach { file ->
            val path = file["path"].textValue()
            errors += file["scan_errors"].map {
                OrtIssue(source = scannerName, message = "${it.textValue()} (File: $path)")
            }
        }

        return ScanSummary(startTime, endTime, fileCount, findings, errors)
    }

    /**
     * Get the SPDX license id (or a fallback) for a license finding.
     */
    private fun getLicenseId(license: JsonNode): String {
        var name = license["spdx_license_key"].textValue()

        if (name.isEmpty()) {
            val key = license["key"].textValue()
            name = if (key == "unknown") {
                "NOASSERTION"
            } else {
                // Starting with version 2.9.8, ScanCode uses "scancode" as a LicenseRef namespace, but only for SPDX
                // output formats, see https://github.com/nexB/scancode-toolkit/pull/1307.
                "LicenseRef-${scannerName.toLowerCase()}-$key"
            }
        }

        return name
    }

    /**
     * Get the license findings from the given [result].
     */
    internal fun getLicenseFindings(result: JsonNode): List<LicenseFinding> =
        result["files"].flatMap { file ->
            file["licenses"]?.toList().orEmpty().map {
                LicenseFinding(
                    license = getLicenseId(it),
                    location = TextLocation(
                        path = file["path"].textValue(),
                        startLine = it["start_line"].intValue(),
                        endLine = it["end_line"].intValue()
                    )
                )
            }
        }

    /**
     * Get the copyright findings from the given [result].
     */
    internal fun getCopyrightFindings(result: JsonNode): List<CopyrightFinding> =
        result["files"].flatMap { file ->
            val path = file["path"].textValue()

            file["copyrights"]?.toList().orEmpty().flatMap { copyrights ->
                val startLine = copyrights["start_line"].intValue()
                val endLine = copyrights["end_line"].intValue()
                // While ScanCode 2.9.2 was still using "statements", version 2.9.7 is using "value".
                val statements = (copyrights["statements"] ?: listOf(copyrights["value"]))

                statements.map { statement ->
                    CopyrightFinding(
                        statement = statement.textValue(),
                        location = TextLocation(
                            path = path,
                            startLine = startLine,
                            endLine = endLine
                        )
                    )
                }
            }
        }

    /**
     * Get the license found in one of the commonly named license files, if any, or an empty string otherwise.
     */
    internal fun getRootLicense(licenseFindings: List<LicenseFinding>): String =
        // TODO: This function should return a list of all licenses found in all license files instead of only a single
        //       license.
        licenseFindings.singleOrNull { finding ->
            try {
                LICENSE_FILE_MATCHERS.any { it.matches(Paths.get(finding.location.path)) }
            } catch (e: InvalidPathException) {
                false
            }
        }?.license ?: ""

    /**
     * Return the copyright statements in the vicinity, as specified by [toleranceLines], of [licenseStartLine] in the
     * file [path]. The default value of [toleranceLines] is set to 5 which seems to be a good balance between
     * associating findings separated by blank lines but not skipping complete license statements.
     */
    internal fun getClosestCopyrightStatements(
        copyrights: List<CopyrightFinding>,
        licenseStartLine: Int,
        toleranceLines: Int = 5
    ): SortedSet<CopyrightFindings> {
        require(copyrights.map { it.location.path }.distinct().size <= 1) {
            "Given copyright statements must all point to the same file."
        }

        val closestCopyrights = copyrights.filter {
            (it.location.startLine - licenseStartLine).absoluteValue <= toleranceLines
        }

        return closestCopyrights.map { it.toCopyrightFindings() }.toSortedSet()
    }

    private fun CopyrightFinding.toCopyrightFindings() =
        CopyrightFindings(
            statement = statement,
            locations = sortedSetOf(location)
        )

    /**
     * Associate copyright findings to license findings within a single file.
     */
    private fun associateFileFindings(
        licenses: List<LicenseFinding>,
        copyrights: List<CopyrightFinding>,
        rootLicense: String = ""
    ): SortedMap<String, MutableSet<CopyrightFindings>> {
        require((licenses.map { it.location.path } + copyrights.map { it.location.path }).distinct().size <= 1) {
            "The given license and copyright findings must all point to the same file."
        }

        val copyrightsForLicenses = sortedMapOf<String, MutableSet<CopyrightFindings>>()
        val allCopyrightStatements = copyrights.map { it.toCopyrightFindings() }.toSortedSet()

        when (licenses.size) {
            0 -> {
                // If there is no license finding but copyright findings, associate them with the root license, if any.
                if (allCopyrightStatements.isNotEmpty() && rootLicense.isNotEmpty()) {
                    copyrightsForLicenses[rootLicense] = allCopyrightStatements
                }
            }

            1 -> {
                // If there is only a single license finding, associate all copyright findings with that license.
                val licenseId = licenses.single().license
                copyrightsForLicenses[licenseId] = allCopyrightStatements
            }

            else -> {
                // If there are multiple license findings in a single file, search for the closest copyright statements
                // for each of these, if any.
                licenses.forEach {
                    val closestCopyrights = getClosestCopyrightStatements(
                        copyrights = copyrights,
                        licenseStartLine = it.location.startLine
                    )
                    copyrightsForLicenses.getOrPut(it.license) { sortedSetOf() } += closestCopyrights
                }
            }
        }

        return copyrightsForLicenses
    }

    /**
     * Associate copyright findings to license findings throughout the whole result.
     */
    internal fun associateFindings(result: JsonNode): SortedSet<LicenseFindings> =
        associateFindings(getLicenseFindings(result), getCopyrightFindings(result))

    private fun associateFindings(licenseFindings: List<LicenseFinding>, copyrightFindings: List<CopyrightFinding>):
            SortedSet<LicenseFindings> {
        val licenseFindingsByPath = licenseFindings.groupBy { it.location.path }
        val copyrightFindingsByPath = copyrightFindings.groupBy { it.location.path }
        val paths = (licenseFindingsByPath.keys + copyrightFindingsByPath.keys).toSet()
        val rootLicense = getRootLicense(licenseFindings)

        val locationsForLicenses = licenseFindings
            .groupBy({ it.license }, { it.location })
            .mapValues { it.value.toSortedSet() }
            .toSortedMap()

        val copyrightsForLicenses = sortedMapOf<String, SortedSet<CopyrightFindings>>()
        paths.forEach { path ->
            val licenses = licenseFindingsByPath[path].orEmpty()
            val copyrights = copyrightFindingsByPath[path].orEmpty()
            val findings = associateFileFindings(licenses, copyrights, rootLicense)

            findings.forEach { (license, copyrightsForLicense) ->
                copyrightsForLicenses.getOrPut(license) { sortedSetOf() }.let { copyrightFindings ->
                    copyrightsForLicense.forEach { copyrightFinding ->
                        copyrightFindings.find { it.statement == copyrightFinding.statement }?.let {
                            it.locations += copyrightFinding.locations
                        } ?: copyrightFindings.add(copyrightFinding)
                    }
                }
            }
        }

        return (copyrightsForLicenses.keys + locationsForLicenses.keys).map { license ->
            LicenseFindings(
                license,
                locationsForLicenses[license] ?: sortedSetOf(),
                copyrightsForLicenses[license] ?: sortedSetOf()
            )
        }.toSortedSet()
    }
}
