package xyz.wagyourtail.unimined.providers.minecraft.patch

import org.gradle.api.Project
import xyz.wagyourtail.unimined.providers.minecraft.EnvType
import xyz.wagyourtail.unimined.providers.minecraft.MinecraftProvider
import java.nio.file.Path

class NoTransformMinecraftTransformer(project: Project, provider: MinecraftProvider) : AbstractMinecraftTransformer(project, provider) {

    override fun transform(minecraft: MinecraftJar): MinecraftJar {
        return minecraft
    }

}