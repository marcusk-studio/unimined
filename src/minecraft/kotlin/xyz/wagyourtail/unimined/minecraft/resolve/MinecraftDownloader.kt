package xyz.wagyourtail.unimined.minecraft.resolve

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.tasks.SourceSetContainer
import xyz.wagyourtail.unimined.api.Constants
import xyz.wagyourtail.unimined.api.Constants.METADATA_URL
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftResolver
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.minecraft.MinecraftProviderImpl
import xyz.wagyourtail.unimined.util.LazyMutable
import xyz.wagyourtail.unimined.util.stream
import xyz.wagyourtail.unimined.util.testSha1
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

@Suppress("MemberVisibilityCanBePrivate")
class MinecraftDownloader(val project: Project, private val parent: MinecraftProviderImpl): MinecraftResolver() {

    companion object {

        fun download(download: Download, path: Path) {

            if (testSha1(download.size, download.sha1, path)) {
                return
            }

            download.url?.stream()?.use {
                Files.copy(it, path, StandardCopyOption.REPLACE_EXISTING)
            }

            if (!testSha1(download.size, download.sha1, path)) {
                throw Exception("Failed to download " + download.url)
            }
        }
    }

    val client by lazy {
        parent.clientSourceSets.isNotEmpty() || parent.combinedSourceSets.isNotEmpty()
    }
    val server by lazy {
        parent.serverSourceSets.isNotEmpty() || parent.combinedSourceSets.isNotEmpty()
    }

    private val sourceSets: SourceSetContainer by lazy { project.extensions.getByType(SourceSetContainer::class.java) }

    fun afterEvaluate() {
        // if <=1.2.5 disable combined automagically
        if (mcVersionCompare(version, "1.3") < 0) {
            parent.disableCombined.set(true)
        }

        project.logger.info("Disable combined: ${parent.disableCombined.get()}")

        if (client) {
            if (parent.disableCombined.get()) {
                project.logger.info("selecting split-jar client for sourceset client")
                parent.client.dependencies.add(
                    project.dependencies.create(
                        "net.minecraft:${dependency.name}:${dependency.version!!}:client"
                    )
                )
            } else {
                //TODO: don't provide merged (breaks fg3 currently...)
                project.logger.info("selecting combined-jar for sourceset client")
                parent.client.dependencies.add(
                    project.dependencies.create(
                        "net.minecraft:${dependency.name}:${dependency.version!!}"
                    )
                )
            }
        }
        if (server) {
            if (parent.disableCombined.get()) {
                project.logger.info("selecting split-jar server for sourceset server")
                parent.server.dependencies.add(
                    project.dependencies.create(
                        "net.minecraft:${dependency.name}:${dependency.version!!}:server"
                    )
                )
            } else {
                //TODO: don't provide merged (breaks fg3 currently...)
                project.logger.info("selecting combined-jar for sourceset server")
                parent.server.dependencies.add(
                    project.dependencies.create(
                        "net.minecraft:${dependency.name}:${dependency.version!!}"
                    )
                )
            }
        }

        if (parent.disableCombined.get()) {
            parent.combined.let {
                it.dependencies.clear()
                if (client) {
                    it.dependencies.add(
                        project.dependencies.create(
                            "net.minecraft:${dependency.name}:${dependency.version!!}:client"
                        )
                    )
                }
                if (server) {
                    it.dependencies.add(
                        project.dependencies.create(
                            "net.minecraft:${dependency.name}:${dependency.version!!}:server"
                        )
                    )
                }
            }
        }
    }


    var dependency: Dependency by LazyMutable {
        val dependencies = parent.combined.dependencies

        if (dependencies.isEmpty()) {
            throw IllegalStateException("No dependencies found for Minecraft")
        }

        if (dependencies.size > 1) {
            throw IllegalStateException("Multiple dependencies found for Minecraft")
        }

        val dependency = dependencies.first()

        if (dependency.group != Constants.MINECRAFT_GROUP) {
            throw IllegalStateException("Invalid dependency group for Minecraft, expected ${Constants.MINECRAFT_GROUP} but got ${dependency.group}")
        }

        if (dependency.name != "minecraft") {
            throw IllegalArgumentException("Dependency $dependency is not a Minecraft dependency")
        }

        dependency
    }

    override val version: String by lazy {
        URLDecoder.decode(dependency.version!!, StandardCharsets.UTF_8.name())
    }

    val launcherMeta by lazy {
        if (project.gradle.startParameter.isOffline) {
            throw IllegalStateException("Offline mode is enabled, but version metadata is not available")
        }

        val urlConnection = METADATA_URL.toURL().openConnection() as HttpURLConnection
        urlConnection.setRequestProperty("User-Agent", "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtal.xyz>)")
        urlConnection.requestMethod = "GET"
        urlConnection.connect()

        if (urlConnection.responseCode != 200) {
            throw IOException("Failed to get metadata, ${urlConnection.responseCode}: ${urlConnection.responseMessage}")
        }

        val versionsList = urlConnection.inputStream.use {
            InputStreamReader(it).use { reader ->
                JsonParser.parseReader(reader).asJsonObject
            }
        }

        versionsList.getAsJsonArray("versions") ?: throw Exception("Failed to get metadata, no versions")
    }

    fun mcVersionCompare(vers1: String, vers2: String): Int {
        if (vers1 == vers2) return 0
        for (i in launcherMeta) {
            if (i.asJsonObject["id"].asString == vers1) {
                return 1
            }
            if (i.asJsonObject["id"].asString == vers2) {
                return -1
            }
        }
        throw Exception("Failed to compare versions, $vers1 and $vers2 are not valid versions")
    }

    val metadata: VersionData by lazy {
        val version = version
        resolveMetadata(version)
    }

    fun resolveMetadata(version: String): VersionData {
        val path = versionJsonDownloadPath(version)
        if (path.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            return parseVersionData(
                InputStreamReader(path.inputStream()).use { reader ->
                    JsonParser.parseReader(reader).asJsonObject
                }
            )
        } else {
            val versionIndex = getVersionFromLauncherMeta(version)
            val downloadPath = versionJsonDownloadPath(version)
            downloadPath.parent.createDirectories()

            if (project.gradle.startParameter.isOffline) {
                throw IllegalStateException("Cannot download version metadata while offline")
            }

            if (!downloadPath.exists() || !testSha1(-1, versionIndex.get("sha1").asString, downloadPath)) {
                val url = versionIndex.get("url").asString
                val urlConnection = URI.create(url).toURL().openConnection() as HttpURLConnection
                urlConnection.setRequestProperty(
                    "User-Agent",
                    "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtal.xyz>)"
                )
                urlConnection.requestMethod = "GET"
                urlConnection.connect()

                if (urlConnection.responseCode != 200) {
                    throw IOException("Failed to get version data, ${urlConnection.responseCode}: ${urlConnection.responseMessage}")
                }

                urlConnection.inputStream.use {
                    Files.write(
                        downloadPath,
                        it.readBytes(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                }

            }

            return parseVersionData(
                InputStreamReader(path.inputStream()).use { reader ->
                    JsonParser.parseReader(reader).asJsonObject
                }
            )
        }
    }

    private fun getVersionFromLauncherMeta(versionId: String): JsonObject {
        for (version in launcherMeta) {
            val versionObject = version.asJsonObject
            val id = versionObject.get("id").asString
            if (id == versionId.substringAfter("empty-")) {
                return versionObject
            }
        }
        throw IllegalStateException("Failed to get metadata, no version found for $versionId")
    }

    private val minecraftClient: Path by lazy {
        val version = version

        val clientPath = clientJarDownloadPath(version)
        if (clientPath.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            clientPath
        } else {
            if (version.startsWith("empty-")) {
                clientPath.outputStream().use {
                    ZipOutputStream(it).close()
                }
                return@lazy clientPath
            }
            val metadata = metadata
            val clientJar = metadata.downloads["client"]
                ?: throw IllegalStateException("No client jar found for version $version")

            clientPath.parent.createDirectories()
            download(clientJar, clientPath)
            clientPath
        }
    }

    private fun getServerVersionOverrides(): Map<String, String> {
        val versionOverrides = mutableMapOf<String, String>()
        val versionOverridesFile = project.unimined.getGlobalCache().resolve("server-version-overrides.json")

        if (!versionOverridesFile.exists()) {
            try {
                URI.create("https://maven.wagyourtail.xyz/releases/mc-c2s.json").stream().use {
                    Files.write(
                        versionOverridesFile,
                        it.readBytes(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                }
            } catch (e: Exception) {
                versionOverridesFile.deleteIfExists()
                throw e
            }
        }

        // read file into json
        if (versionOverridesFile.exists()) {
            InputStreamReader(versionOverridesFile.inputStream()).use {
                val json = JsonParser.parseReader(it).asJsonObject
                for (entry in json.entrySet()) {
                    versionOverrides[entry.key] = entry.value.asString
                }
            }
        }

        return versionOverrides
    }

    private val minecraftServer: Path by lazy {
        val version = version

        val serverPath = serverJarDownloadPath(version)
        if (serverPath.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            serverPath
        } else {
            if (version.startsWith("empty-")) {
                serverPath.outputStream().use {
                    ZipOutputStream(it).close()
                }
                return@lazy serverPath
            }
            val metadata = metadata
            var serverJar = metadata.downloads["server"]

            if (serverJar == null) {
                // attempt to get off betacraft
                val serverVersion = getServerVersionOverrides().getOrDefault(version, version)


                val uriPart = if (version.startsWith("b")) {
                    "beta/${parent.serverVersionOverride.getOrElse(serverVersion)}"
                } else if (version.startsWith("a")) {
                    "alpha/${
                        parent.serverVersionOverride.getOrElse(serverVersion)
                    }"
                } else {
                    val folder = version.split(".").subList(0, 2).joinToString(".")
                    "release/$folder/${parent.serverVersionOverride.getOrElse(serverVersion)}"
                }
                serverJar = Download("", -1, URI.create("http://files.betacraft.uk/server-archive/$uriPart.jar"))
            }

            serverPath.parent.createDirectories()
            try {
                download(serverJar, serverPath)
            } catch (e: FileNotFoundException) {
                throw IllegalStateException("No server jar found for version $version", e)
            }
            serverPath
        }
    }

    private val clientMappings: Path by lazy {
        val mappings = metadata.downloads["client_mappings"]
            ?: throw IllegalStateException("No client mappings found for version $version")
        val mappingsPath = clientMappingsDownloadPath(version)

        if (mappingsPath.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            mappingsPath
        } else {
            mappingsPath.parent.createDirectories()
            download(mappings, mappingsPath)
            mappingsPath
        }
    }

    fun getMinecraft(envType: EnvType): Path {
        return when (envType) {
            EnvType.CLIENT -> minecraftClient
            EnvType.SERVER -> minecraftServer
            EnvType.COMBINED -> throw IllegalStateException("This should be handled at mcprovider by calling transformer merge")
        }
    }

    private val serverMappings: Path by lazy {
        val mappings = metadata.downloads["server_mappings"]
            ?: throw IllegalStateException("No server mappings found for version $version")
        val mappingsPath = serverMappingsDownloadPath(version)

        if (mappingsPath.exists() && !project.gradle.startParameter.isRefreshDependencies) {
            mappingsPath
        } else {
            mappingsPath.parent.createDirectories()
            download(mappings, mappingsPath)
            mappingsPath
        }
    }

    fun getMappings(envType: EnvType): Path {
        return when (envType) {
            EnvType.CLIENT -> clientMappings
            EnvType.SERVER -> serverMappings
            EnvType.COMBINED -> clientMappings
        }
    }

    fun extract(dependency: Dependency, extract: Extract, path: Path) {
        val resolved = parent.mcLibraries.resolvedConfiguration
        resolved.getFiles { it == dependency }.forEach { file ->
            ZipInputStream(file.inputStream()).use { stream ->
                var entry = stream.nextEntry
                while (entry != null) {
                    if (entry.isDirectory) {
                        entry = stream.nextEntry
                        continue
                    }
                    if (extract.exclude.any { entry!!.name.startsWith(it) }) {
                        entry = stream.nextEntry
                        continue
                    }
                    path.resolve(entry.name).parent.createDirectories()
                    Files.copy(stream, path.resolve(entry.name), StandardCopyOption.REPLACE_EXISTING)
                    entry = stream.nextEntry
                }
            }
        }
    }

    fun mcVersionFolder(version: String): Path {
        return project.unimined.getGlobalCache()
            .resolve("net")
            .resolve("minecraft")
            .resolve("minecraft")
            .resolve(version)
    }

    fun clientJarDownloadPath(version: String): Path {
        return mcVersionFolder(version).resolve("minecraft-$version-client.jar")
    }

    fun serverJarDownloadPath(version: String): Path {
        return mcVersionFolder(version).resolve("minecraft-$version-server.jar")
    }

    fun clientMappingsDownloadPath(version: String): Path {
        return mcVersionFolder(version).resolve("client_mappings.txt")
    }

    fun serverMappingsDownloadPath(version: String): Path {
        return mcVersionFolder(version).resolve("server_mappings.txt")
    }

    fun versionJsonDownloadPath(version: String): Path {
        return mcVersionFolder(version).resolve("version.json")
    }
}