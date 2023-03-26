package xyz.wagyourtail.unimined.minecraft.resolve

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import xyz.wagyourtail.unimined.api.Constants.ASSET_BASE_URL
import xyz.wagyourtail.unimined.api.unimined
import xyz.wagyourtail.unimined.minecraft.MinecraftProviderImpl
import xyz.wagyourtail.unimined.util.stream
import xyz.wagyourtail.unimined.util.testSha1
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream

class AssetsDownloader(val project: Project, private val parent: MinecraftProviderImpl) {

    fun downloadAssets(project: Project, assets: AssetIndex): Path {
        val dir = assetsDir()
        val index = dir.resolve("indexes").resolve("${assets.id}.json")

        index.parent.createDirectories()

        updateIndex(assets, index)

        val assetsJson = index.inputStream().use {
            JsonParser.parseReader(InputStreamReader(it)).asJsonObject
        }

        resolveAssets(project, assetsJson, dir)
        return dir
    }

    fun assetsDir(): Path {
        return project.unimined.getGlobalCache().resolve("assets")
    }

    private fun updateIndex(assets: AssetIndex, index: Path) {
        if (testSha1(assets.size, assets.sha1!!, index)) {
            return
        }

        assets.url?.stream()?.use {
            Files.copy(it, index, StandardCopyOption.REPLACE_EXISTING)
        }

        if (!testSha1(assets.size, assets.sha1, index)) {
            throw Exception("Failed to download " + assets.url)
        }
    }

    companion object {
        @Synchronized
        private fun resolveAssets(project: Project, assetsJson: JsonObject, dir: Path) {
            project.logger.lifecycle("Resolving assets...")
            val copyToResources = assetsJson.get("map_to_resources")?.asBoolean ?: false
            for (key in assetsJson.keySet()) {
                val keyDir = dir.resolve(key)
                val value = assetsJson.get(key)
                if (value is JsonObject) {
                    val entries = value.entrySet()
                    project.logger.lifecycle("Resolving $key (${entries.size} files)...")
                    var downloaded = AtomicInteger(0)
                    val timeStart = System.currentTimeMillis()
                    entries.parallelStream().forEach { (key, value) ->
                        val size = value.asJsonObject.get("size").asLong
                        val hash = value.asJsonObject.get("hash").asString
                        val assetPath = keyDir.resolve(hash.substring(0, 2)).resolve(hash)
                        val assetUrl = URI.create("$ASSET_BASE_URL${hash.substring(0, 2)}/$hash")

                        var i = 0
                        while (!testSha1(size, hash, assetPath) && i < 3) {
                            if (i != 0) {
                                project.logger.warn("Failed to download asset $key : $assetUrl")
                            }
                            i += 1
                            try {
                                project.logger.info("Downloading $key : $assetUrl")
                                assetPath.parent.createDirectories()

                                val urlConnection = assetUrl.toURL().openConnection() as HttpURLConnection

                                urlConnection.connectTimeout = 5000
                                urlConnection.readTimeout = 5000

                                urlConnection.addRequestProperty(
                                    "User-Agent",
                                    "Wagyourtail/Unimined 1.0 (<wagyourtail@wagyourtal.xyz>)"
                                )
                                urlConnection.addRequestProperty("Accept", "*/*")
                                urlConnection.addRequestProperty("Accept-Encoding", "gzip, deflate")

                                if (urlConnection.responseCode == 200) {
                                    urlConnection.inputStream.use {
                                        Files.copy(it, assetPath, StandardCopyOption.REPLACE_EXISTING)
                                    }
                                } else {
                                    project.logger.error("Failed to download asset $key $assetUrl error code: ${urlConnection.responseCode}")
                                }
                            } catch (e: Exception) {
                                project.logger.error("Failed to download asset $key : $assetUrl", e)
                            }
                        }

                        if (i == 3 && !testSha1(size, hash, assetPath)) {
                            throw IOException("Failed to download asset $key : $assetUrl")
                        }

                        if (copyToResources) {
                            val resourcePath = project.projectDir.resolve("run")
                                .resolve("client")
                                .resolve("resources")
                                .resolve(key)
                            resourcePath.parentFile.mkdirs()
                            assetPath.inputStream()
                                .use { Files.copy(it, resourcePath.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                        }

                        if (project.logger.isDebugEnabled) {
                            project.logger.debug("${downloaded.addAndGet(1) * 100 / entries.size}% (${downloaded}/${entries.size})\n")
                        } else {
                            print("${downloaded.addAndGet(1) * 100 / entries.size}% (${downloaded}/${entries.size})\r")
                            System.out.flush()
                        }
                    }
                    val timeEnd = System.currentTimeMillis()
                    project.logger.lifecycle("Finished resolving $key in ${timeEnd - timeStart}ms")
                }
            }
        }
    }
}