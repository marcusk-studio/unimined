package xyz.wagyourtail.unimined

import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.tasks.SourceSet
import xyz.wagyourtail.unimined.api.UniminedExtension
import xyz.wagyourtail.unimined.api.source.task.MigrateMappingsTask
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.internal.mapping.task.MigrateMappingsTaskImpl
import xyz.wagyourtail.unimined.internal.minecraft.MinecraftProvider
import xyz.wagyourtail.unimined.internal.minecraft.patch.reindev.ReIndevProvider
import xyz.wagyourtail.unimined.util.capitalized
import xyz.wagyourtail.unimined.util.defaultedMapOf
import xyz.wagyourtail.unimined.util.withSourceSet
import java.net.URI
import java.nio.file.Path

open class UniminedExtensionImpl(project: Project) : UniminedExtension(project) {

    override val minecrafts = mutableMapOf<SourceSet, MinecraftConfig>()
    override val minecraftConfiguration = mutableMapOf<SourceSet, MinecraftConfig.() -> Unit>()

    override fun minecraft(sourceSet: SourceSet, lateApply: Boolean, action: MinecraftConfig.() -> Unit) {
        if (minecrafts.containsKey(sourceSet)) {
            if ((minecrafts[sourceSet] as MinecraftProvider).applied) {
                throw IllegalStateException("minecraft config for ${sourceSet.name} already applied, cannot configure further!")
            } else {
                project.logger.info("[Unimined] registering minecraft config for ${sourceSet.name}")
            }
        } else {
            minecrafts[sourceSet] = MinecraftProvider(project, sourceSet)
        }
        minecraftConfiguration.compute(sourceSet) { _, old ->
            if (old != null) {
                {
                    old()
                    action()
                }
            } else {
                action
            }
        }
        minecrafts[sourceSet]?.action()
        if (!lateApply) (minecrafts[sourceSet] as MinecraftProvider).apply()
    }

    override fun reIndev(sourceSet: SourceSet, lateApply: Boolean, action: MinecraftConfig.() -> Unit) {
        if (minecrafts.containsKey(sourceSet)) {
            if ((minecrafts[sourceSet] as ReIndevProvider).applied) {
                throw IllegalStateException("minecraft config for ${sourceSet.name} already applied, cannot configure further!")
            } else {
                project.logger.info("[Unimined] registering reIndev config for ${sourceSet.name}")
            }
        } else {
            minecrafts[sourceSet] = ReIndevProvider(project, sourceSet)
        }
        minecraftConfiguration.compute(sourceSet) { _, old ->
            if (old != null) {
                {
                    old()
                    action()
                }
            } else {
                action
            }
        }
        minecrafts[sourceSet]?.action()
        if (!lateApply) (minecrafts[sourceSet] as ReIndevProvider).apply()
    }

    override fun migrateMappings(sourceSet: SourceSet, action: MigrateMappingsTask.() -> Unit) {
//        MigrateMappingsTaskImpl(project, sourceSet).apply(action)
        project.tasks.create("migrateMappings".withSourceSet(sourceSet), MigrateMappingsTaskImpl::class.java, sourceSet).apply(action)
    }

    private fun getMinecraftDepNames(): Set<String> = minecrafts.values.map { (it as MinecraftProvider).minecraftDepName }.toSet()

    private fun depNameToSourceSet(depName: String): SourceSet {
        val sourceSetName = depName.substringAfter("+", "main")
        return minecrafts.keys.find { it.name == sourceSetName } ?: throw IllegalArgumentException("no source set found for $sourceSetName")
    }

    override val modsRemapRepo = project.repositories.flatDir {
        it.name = "modsRemap"
        it.dir(getLocalCache().resolve("modTransform").toFile())
        it.content {
            it.includeGroupByRegex("remapped_.+")
        }
    }

    @delegate:Transient
    val minecraftForgeMaven by lazy {
        project.repositories.maven {
            it.name = "minecraftForge"
            it.url = URI("https://maven.minecraftforge.net/")
            it.metadataSources {
                it.mavenPom()
                it.artifact()
            }
        }
    }

    override fun minecraftForgeMaven() {
        project.logger.info("[Unimined] adding Minecraft Forge maven: $minecraftForgeMaven")
    }

    @delegate:Transient
    val neoForgedMaven by lazy {
        project.repositories.maven {
            it.name = "neoForged"
            it.url = URI("https://maven.neoforged.net/releases")
            it.metadataSources {
                it.mavenPom()
                it.artifact()
            }
        }
    }

    override fun neoForgedMaven() {
        project.logger.info("[Unimined] adding Neo-Forged maven: $neoForgedMaven")
    }

    @delegate:Transient
    val fabricMaven by lazy {
        project.repositories.maven {
            it.name = "fabric"
            it.url = URI.create("https://maven.fabricmc.net")
        }
    }

    override fun fabricMaven() {
        project.logger.info("[Unimined] adding Fabric maven: $fabricMaven")
    }

    @delegate:Transient
    val ornitheMaven by lazy {
        project.repositories.maven {
            it.name = "ornithe"
            it.url = URI.create("https://maven.ornithemc.net/releases")
        }
    }
    override fun ornitheMaven() {
        project.logger.info("[Unimined] adding Ornithe maven: $ornitheMaven")
    }

    @delegate:Transient
    val legacyFabricMaven by lazy {
        project.repositories.maven {
            it.name = "legacyFabric"
            it.url = URI.create("https://maven.legacyfabric.net")
        }
    }
    override fun legacyFabricMaven() {
        project.logger.info("[Unimined] adding Legacy Fabric maven: $legacyFabricMaven")
    }

    @delegate:Transient
    val quiltMaven by lazy {
        project.repositories.maven {
            it.name = "quilt"
            it.url = URI.create("https://maven.quiltmc.org/repository/release")
        }
    }

    override fun quiltMaven() {
        project.logger.info("[Unimined] adding Quilt maven: $quiltMaven")
    }

    @Deprecated("Use glassLauncherMaven(\"babric\") instead", ReplaceWith("glassLauncherMaven(\"babric\")"))
    override fun babricMaven() {
        glassLauncherMaven("babric")
    }

    @field:Transient
    val glassLauncherMaven = defaultedMapOf<String, MavenArtifactRepository> { name ->
        project.repositories.maven {
            it.name = "Glass (${name.capitalized()})"
            it.url = URI.create("https://maven.glass-launcher.net/$name/")
        }
    }

    override fun glassLauncherMaven(name: String) {
        project.logger.info("[Unimined] adding Glass Launcher maven: ${glassLauncherMaven[name]}")
    }

    @field:Transient
    val wagYourMaven = defaultedMapOf<String, MavenArtifactRepository> { name ->
        project.repositories.maven {
            it.name = "WagYourTail (${name.capitalized()})"
            it.url = project.uri("https://maven.wagyourtail.xyz/$name/")
            it.metadataSources { ms ->
                ms.mavenPom()
                ms.artifact()
            }
        }
    }

    override fun wagYourMaven(name: String) {
        project.logger.info("[Unimined] adding WagYourTail maven: ${wagYourMaven[name]}")
    }

    @delegate:Transient
    val mcphackersIvy by lazy {
        project.repositories.ivy { ivy ->
            ivy.name = "mcphackers"
            ivy.url = URI.create("https://mcphackers.github.io/versionsV2/")
            ivy.patternLayout {
                it.artifact("[revision].[ext]")
            }
            ivy.content {
                it.includeModule("io.github.mcphackers", "mcp")
            }
            ivy.metadataSources {
                it.artifact()
            }
        }
    }

    override fun mcphackersIvy() {
        project.logger.info("[Unimined] adding mcphackers ivy: $mcphackersIvy")
    }

    @delegate:Transient
    val parchmentMaven by lazy {
        project.repositories.maven {
            it.name = "parchment"
            it.url = URI.create("https://maven.parchmentmc.org/")
        }
    }

    override fun parchmentMaven() {
        project.logger.info("[Unimined] adding parchment maven: $parchmentMaven")
    }

    @delegate:Transient
    val sonatypeStaging by lazy {
        project.repositories.maven {
            it.name = "sonatypeStaging"
            it.url = URI.create("https://oss.sonatype.org/content/repositories/staging/")
        }
    }

    override fun sonatypeStaging() {
        project.logger.info("[Unimined] adding sonatype staging maven: $sonatypeStaging")
    }

    @delegate:Transient
    val spongeMaven by lazy {
        project.repositories.maven {
            it.name = "Sponge"
            it.url = URI.create("https://repo.spongepowered.org/maven/")
        }
    }

    override fun spongeMaven() {
        project.logger.info("[Unimined] adding sponge maven: $spongeMaven")
    }

    @delegate:Transient
    val jitpack by lazy {
        project.repositories.maven {
            it.name = "jitpack"
            it.url = URI.create("https://jitpack.io")
        }
    }

    override fun jitpack() {
        project.logger.info("[Unimined] adding jitpack maven: $jitpack")
    }

    @delegate:Transient
    val spigot by lazy {
        project.repositories.maven {
            it.name = "spigot"
            it.url = URI.create("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        }
    }

    override fun spigot() {
        project.logger.info("[Unimined] adding spigot maven: $spigot")
    }

    @field:Transient
    val flintMaven = defaultedMapOf<String, MavenArtifactRepository> { name ->
        project.repositories.maven {
            it.name = "Flint (${name.capitalized()})"
            it.url = URI.create("https://maven.flintloader.net/$name/")
        }
    }

    override fun flintMaven(name: String) {
        project.logger.info("[Unimined] adding Flint Loader maven: ${flintMaven[name]}")
    }

    @field:Transient
    val cleanroomMaven = defaultedMapOf<String, MavenArtifactRepository> { name ->
        project.repositories.maven {
            it.name = "cleanroom-$it"
            it.url = URI.create("https://repo.cleanroommc.com/$name")
        }
    }

    override fun cleanroomRepos() {
        project.logger.info("[Unimined] adding cleanroom maven: ${cleanroomMaven["snapshots"]}")
        project.logger.info("[Unimined] adding cleanroom maven: ${cleanroomMaven["releases"]}")
    }

    @delegate:Transient
    val outlandsMaven by lazy {
        project.repositories.maven {
            it.name = "outlands"
            it.url = URI.create("https://maven.outlands.top/releases")
        }
    }

    override fun outlandsMaven() {
        project.logger.info("[Unimined] adding outlands maven: $outlandsMaven")
    }

    @delegate:Transient
    val fox2codeMaven by lazy {
        project.repositories.maven {
            it.name = "Fox2Code"
            it.url = URI.create("https://cdn.fox2code.com/maven")
        }
    }

    override fun fox2codeMaven() {
        project.logger.info("[Unimined] adding Fox2Code maven: $fox2codeMaven")
    }

    @delegate:Transient
    val modrinthMaven by lazy {
        project.repositories.exclusiveContent { repository ->
            repository.forRepository {
                project.repositories.maven {
                    it.name = "modrinth"
                    it.url = URI.create("https://api.modrinth.com/maven")
                }
            }

            repository.filter { descriptor ->
                descriptor.includeGroup("maven.modrinth")
            }
        }
    }

    @delegate:Transient
    val backupModrinthMaven by lazy {
        project.repositories.maven {
            it.name = "modrinth"
            it.url = URI.create("https://api.modrinth.com/maven")
            it.content {
                it.includeGroup("maven.modrinth")
            }
        }
    }

    override fun modrinthMaven() {
        try {
            project.logger.info("[Unimined] adding Modrinth maven: $modrinthMaven")
        } catch (e: Throwable) {
            project.logger.warn("[Unimined] failed to add Modrinth maven, falling back to backup")
            project.logger.warn(e.stackTraceToString())
            project.logger.info("[Unimined] adding Modrinth maven: $backupModrinthMaven")
        }
    }

    @delegate:Transient
    val curseMaven by lazy {
        project.repositories.exclusiveContent {
            it.forRepository {
                project.repositories.maven {
                    it.name = "cursemaven"
                    it.url = URI.create("https://cursemaven.com")
                }
            }

            it.filter {
                it.includeGroup("curse.maven")
            }
        }
    }

    @delegate:Transient
    val backupCurseMaven by lazy {
        project.repositories.maven {
            it.name = "cursemaven"
            it.url = URI.create("https://cursemaven.com")

            it.content {
                it.includeGroup("curse.maven")
            }
        }
    }

    @delegate:Transient
    val betaCurseMaven by lazy {
        project.repositories.exclusiveContent {
            it.forRepository {
                project.repositories.maven {
                    it.name = "beta-cursemaven"
                    it.url = URI.create("https://beta.cursemaven.com")
                }
            }

            it.filter {
                it.includeGroup("curse.maven")
            }
        }
    }

    @delegate:Transient
    val backupBetaCurseMaven by lazy {
        project.repositories.maven {
            it.name = "beta-cursemaven"
            it.url = URI.create("https://beta.cursemaven.com")

            it.content {
                it.includeGroup("curse.maven")
            }
        }
    }

    override fun curseMaven(beta: Boolean /* = false */) {
        try {
            project.logger.info("[Unimined] adding Curse maven: ${if (beta) betaCurseMaven else curseMaven}")
        } catch (e: Throwable) {
            project.logger.warn("[Unimined] failed to add Curse maven, falling back to backup")
            project.logger.warn(e.stackTraceToString())
            project.logger.info("[Unimined] adding Curse maven: ${if (beta) backupBetaCurseMaven else backupCurseMaven}")
        }
    }

    init {
        project.repositories.mavenCentral { repo ->
            repo.content {
                // don't need to look here.
                it.excludeGroup("com.mojang")
                // 1.21 natives-macos-patch missing
                it.excludeGroup("org.lwjgl")
                // native jar missing on central
                it.excludeGroup("ca.weblite")
            }
        }
        project.repositories.maven {
            it.name = "minecraft"
            it.url = URI.create("https://libraries.minecraft.net/")
        }
        project.repositories.all { repo ->
            if (repo != modsRemapRepo) {
                repo.content {
                    it.excludeGroupByRegex("remapped_.+")
                }
            }
        }
        project.afterEvaluate {
            afterEvaluate()
        }
    }

    private fun getSourceSetFromMinecraft(path: Path): SourceSet? {
        for ((set, mc) in minecrafts) {
            if (mc.isMinecraftJar(path)) {
                return set
            }
        }
        return null
    }

    private fun afterEvaluate() {
        for ((sourceSet, mc) in minecrafts) {
            (mc as MinecraftProvider).afterEvaluate()
            val mcFiles = sourceSet.runtimeClasspath.files.mapNotNull { getSourceSetFromMinecraft(it.toPath()) }
            if (mcFiles.size > 1) {
                throw IllegalStateException("multiple minecraft jars in runtime classpath of $sourceSet, from $mcFiles")
            }
        }
    }
}
