plugins {
    java
    // The plugin is written in Kotlin (src/main/kotlin). Kotlin 2.0.x targets JVM 8 bytecode.
    kotlin("jvm") version "2.2.21"
}

group = "dev.etran"
version = "2.2.0"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    // Only needed if you pass -PwithBwApi (see below).
    maven("https://repo.andrei1058.dev/releases/")
}

dependencies {
    // Bundled into the jar below: 1.8-1.15 servers cannot download libraries, so the Kotlin runtime ships inside.
    implementation(kotlin("stdlib"))

    // Compile against the OLDEST supported API (1.8.8) so nothing newer can be called by accident.
    // Newer features are reached by name/reflection in dev.etran.compat and degrade gracefully, which is what
    // lets this single Java 8 jar run on Spigot/Paper from 1.8 through the latest release.
    // (bungeecord-chat is excluded: the plugin never touches chat components and its old snapshot is gone.)
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }

    // The plugin drives Bedwars1058 through its setup commands (the setup session is not part of the
    // public API), so the API jar is optional. Enable with: ./gradlew build -PwithBwApi -PbwApiVersion=<version>
    // The standalone checks (./gradlew inferenceCheck / placementCheck) construct Bukkit types, so tests see the API too.
    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }

    if (project.hasProperty("withBwApi")) {
        val bwApi = (project.findProperty("bwApiVersion") ?: "22.0") as String
        compileOnly("com.andrei1058.bedwars:bedwars-api:$bwApi")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(8)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    // Java 8 bytecode: 1.8-1.16 servers usually run Java 8, newer servers run 16-25, and all load Java 8 classes.
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveBaseName.set("BedWars1058-EasySetup")
    archiveVersion.set("v${project.version}")
}

// Runs the detection algorithms against a synthetic map (no server needed): ./gradlew inferenceCheck
tasks.register<JavaExec>("inferenceCheck") {
    group = "verification"
    description = "Checks generator and base detection on a synthetic 4-base map."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.etran.infer.InferenceCheck")
}

// Runs the placement logic checks (facing maths, arena file parsing, deletion): ./gradlew placementCheck
tasks.register<JavaExec>("placementCheck") {
    group = "verification"
    description = "Checks shop facing, arena file parsing and element deletion without a server."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.etran.tools.PlacementCheck")
}

// One self-contained jar: copy the Kotlin runtime (the only runtime dependency) into the plugin jar.
tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
}
