plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}
group = "dev.idlerewards"
version = "0.1.1"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.processResources {
    // Ohne diese Zeile bleibt processResources bei einem reinen Versionswechsel
    // UP-TO-DATE und das Jar traegt die ALTE Version im plugin.yml (gefunden 08.09.2026).
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}
