plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "com.worldgit"
val pluginVersion = providers.gradleProperty("pluginVersion").getOrElse("1.1.0")
version = pluginVersion

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://repo.onarandombox.com/content/groups/public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("net.dmulloy2:ProtocolLib:5.4.0")
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.5.3")
    compileOnly("net.luckperms:api:5.4")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

tasks {
    val installWebUi by registering(Exec::class) {
        workingDir(rootProject.file("web-ui"))
        inputs.file(rootProject.file("web-ui/package.json"))
        inputs.file(rootProject.file("web-ui/pnpm-lock.yaml"))
        outputs.dir(rootProject.file("web-ui/node_modules"))
        commandLine("pnpm", "install", "--frozen-lockfile")
    }

    val buildWebUi by registering(Exec::class) {
        workingDir(rootProject.file("web-ui"))
        dependsOn(installWebUi)
        inputs.dir(rootProject.file("web-ui/src"))
        inputs.file(rootProject.file("web-ui/build.mjs"))
        inputs.file(rootProject.file("web-ui/package.json"))
        inputs.file(rootProject.file("web-ui/tsconfig.json"))
        outputs.file(rootProject.file("src/main/resources/web/index.html"))
        commandLine("pnpm", "build")
    }

    shadowJar {
        archiveClassifier.set("")
        // sqlite-jdbc 依赖 JNI 导出的固定类名，重定位会导致原生方法链接失败。
    }
    build {
        dependsOn(shadowJar)
    }
    processResources {
        dependsOn(buildWebUi)
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
