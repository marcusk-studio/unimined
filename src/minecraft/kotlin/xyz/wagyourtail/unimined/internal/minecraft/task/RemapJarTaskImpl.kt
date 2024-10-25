package xyz.wagyourtail.unimined.internal.minecraft.task

import kotlinx.coroutines.runBlocking
import net.fabricmc.loom.util.kotlin.KotlinClasspathService
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.internal.mapping.aw.AccessWidenerApplier
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension
import xyz.wagyourtail.unimined.mapping.Namespace
import xyz.wagyourtail.unimined.util.*
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.outputStream

abstract class RemapJarTaskImpl @Inject constructor(provider: MinecraftConfig):
    AbstractRemapJarTaskImpl(provider), RemapJarTask {

    private fun afterRemap(afterRemapJar: Path) {
        // merge in manifest from input jar
        afterRemapJar.readZipInputStreamFor("META-INF/MANIFEST.MF", false) { inp ->
            // write to temp file
            val inpTmp = temporaryDir.toPath().resolve("input-manifest.MF")
            inpTmp.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                inp.copyTo(out)
            }
            this.manifest {
                it.from(inpTmp)
            }
        }
        // copy into output
        from(project.zipTree(afterRemapJar))
        copy()
    }

    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    override fun doRemap(
        from: Path,
        target: Path,
        fromNs: Namespace,
        toNs: Namespace,
        classpathList: Array<Path>
    ): Unit = runBlocking {
        project.logger.info("[Unimined/RemapJar ${path}] remapping $fromNs -> $toNs (start time: ${System.currentTimeMillis()})")
        val remapperB = TinyRemapper.newRemapper()
            .withMappings(
                provider.mappings.getTRMappings(
                    fromNs to toNs,
                    false
                )
            )
            .skipLocalVariableMapping(true)
            .ignoreConflicts(true)
            .threads(Runtime.getRuntime().availableProcessors())
        val classpath = KotlinClasspathService.getOrCreateIfRequired(project)
        if (classpath != null) {
            remapperB.extension(KotlinRemapperClassloader.create(classpath).tinyRemapperExtension)
        }
        val betterMixinExtension = MixinRemapExtension(
            project.gradle.startParameter.logLevel,
            allowImplicitWildcards
        )
        betterMixinExtension.enableBaseMixin()
        mixinRemapOptions(betterMixinExtension)
        remapperB.extension(betterMixinExtension)
        provider.minecraftRemapper.tinyRemapperConf(remapperB)
        val remapper = remapperB.build()
        val tag = remapper.createInputTag()
        project.logger.debug("[Unimined/RemapJar ${path}] input: $from")
        betterMixinExtension.readClassPath(remapper, *classpathList).thenCompose {
            project.logger.info("[Unimined/RemapJar ${path}] reading input: $from (time: ${System.currentTimeMillis()})")
            betterMixinExtension.readInput(remapper, tag, from)
        }.thenRun {
            project.logger.info("[Unimined/RemapJar ${path}] writing output: $target (time: ${System.currentTimeMillis()})")
            target.parent.createDirectories()
            try {
                runBlocking {
                    OutputConsumerPath.Builder(target).build().use {
                        it.addNonClassFiles(
                            from,
                            remapper,
                            listOf(
                                AccessWidenerApplier.AwRemapper(
                                    AccessWidenerApplier.nsName(provider.mappings, fromNs),
                                    AccessWidenerApplier.nsName(provider.mappings, toNs),
                                ),
                                AccessTransformerApplier.AtRemapper(
                                    project.logger,
                                    fromNs,
                                    toNs,
                                    isLegacy = remapATToLegacy.getOrElse((provider.mcPatcher as? ForgeLikePatcher<*>)?.remapAtToLegacy == true)!!,
                                    mappings = provider.mappings.resolve()
                                ),
                            )
                        )
                        remapper.apply(it, tag)
                    }
                }
            } catch (e: Exception) {
                target.deleteIfExists()
                throw e
            }
            remapper.finish()
            target.openZipFileSystem(mapOf("mutable" to true)).use {
                betterMixinExtension.insertExtra(tag, it)
            }
            project.logger.info("[Unimined/RemapJar ${path}] remapped $fromNs -> $toNs (end time: ${System.currentTimeMillis()})")
        }.join()
    }

}