package xyz.wagyourtail.unimined.providers.patch.jarmod

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.TaskContainer
import org.gradle.configurationcache.extensions.capitalized
import xyz.wagyourtail.unimined.Constants
import xyz.wagyourtail.unimined.providers.MinecraftProvider
import xyz.wagyourtail.unimined.providers.minecraft.EnvType
import xyz.wagyourtail.unimined.providers.patch.AbstractMinecraftTransformer
import xyz.wagyourtail.unimined.providers.patch.MinecraftJar
import xyz.wagyourtail.unimined.providers.patch.modloader.ModLoaderPatches
import xyz.wagyourtail.unimined.util.consumerApply
import xyz.wagyourtail.unimined.util.deleteRecursively
import java.net.URI
import java.nio.file.*
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

open class JarModMinecraftTransformer(
    project: Project,
    provider: MinecraftProvider,
    private val jarModProvider: String = Constants.JARMOD_PROVIDER,
) : AbstractMinecraftTransformer(
    project, provider
) {

    init {
        for (envType in EnvType.values()) {
            jarModConfiguration(envType)
        }
    }

    private val transform = mutableListOf<(FileSystem) -> Unit>(
        ModLoaderPatches::fixURIisNotHierarchicalException,
        ModLoaderPatches::fixLoadingModFromOtherPackages
    )

    fun addTransform(pathFilter: (FileSystem) -> Unit) {
        transform.add(pathFilter)
    }

    fun jarModConfiguration(envType: EnvType): Configuration {
        return project.configurations.maybeCreate(jarModProvider + (envType.classifier?.capitalized() ?: ""))
    }

    var clientMainClass: String? = null
    var serverMainClass: String? = null


    private val combinedNamesMap = mutableMapOf<EnvType, String>()
    private fun getCombinedNames(envType: EnvType): String {
        return combinedNamesMap.computeIfAbsent(envType) {
            val thisEnv = jarModConfiguration(envType).dependencies.toMutableSet()
            if (envType != EnvType.COMBINED) {
                thisEnv.addAll(jarModConfiguration(EnvType.COMBINED).dependencies)
            }
            val jarMod = thisEnv.sortedBy { "${it.name}-${it.version}" }
            jarMod.joinToString("+") { it.name + "-" + it.version }
        }
    }

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        val combinedNames = getCombinedNames(minecraft.envType)
        if (combinedNames.isEmpty()) {
            return minecraft
        }
        return minecraft.let(consumerApply {
            val target = MinecraftJar(
                minecraft,
                patches = minecraft.patches + combinedNames
            )
            if (target.path.exists() && !project.gradle.startParameter.isRefreshDependencies) {
                return@consumerApply target
            }

            val jarmod = jarModConfiguration(envType).resolve().toMutableSet()
            if (envType != EnvType.COMBINED) {
                jarmod.addAll(jarModConfiguration(EnvType.COMBINED).resolve())
            }

            Files.copy(path, target.path, StandardCopyOption.REPLACE_EXISTING)
            val mc = URI.create("jar:${target.path.toUri()}")
            try {
                FileSystems.newFileSystem(mc, mapOf("mutable" to true), null).use { out ->
                    if (out.getPath("META-INF").exists()) {
                        out.getPath("META-INF").deleteRecursively()
                    }
                    for (file in jarmod) {
                        ZipInputStream(file.inputStream()).use {
                            var entry = it.nextEntry
                            while (entry != null) {
                                if (entry.isDirectory) {
                                    Files.createDirectories(out.getPath(entry.name))
                                } else {
                                    out.getPath(entry.name).parent?.let { path ->
                                        Files.createDirectories(path)
                                    }
                                    Files.write(
                                        out.getPath(entry.name),
                                        it.readBytes(),
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.TRUNCATE_EXISTING
                                    )
                                }
                                entry = it.nextEntry
                            }
                        }
                    }
                    transform.forEach { it(out) }
                }
            } catch (e: Throwable) {
                target.path.deleteIfExists()
                throw e
            }
            target
        })
    }

    override fun applyClientRunConfig(tasks: TaskContainer) {
        provider.provideVanillaRunClientTask(tasks) {
            if (clientMainClass != null) {
                it.mainClass = clientMainClass as String
            }
        }
    }

    override fun applyServerRunConfig(tasks: TaskContainer) {
        provider.provideVanillaRunServerTask(tasks) {
            if (serverMainClass != null) {
                it.mainClass = serverMainClass as String
            }
        }
    }
}