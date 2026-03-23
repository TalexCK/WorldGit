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
    compileOnly("org.mvplugins.multiverse.core:multiverse-core:5.5.3")
    compileOnly("net.luckperms:api:5.4")
    implementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        // sqlite-jdbc 依赖 JNI 导出的固定类名，重定位会导致原生方法链接失败。
    }
    build {
        dependsOn(shadowJar)
    }
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
