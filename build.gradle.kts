plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.13.3" // 必须使用1.x版本兼容2022.3
}

group = "com.yourname.devpulse"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
}

intellij {
    version.set("2022.3.3")
    type.set("IC") // IC = Community, IU = Ultimate
    plugins.set(listOf("com.intellij.java"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.encoding = "UTF-8"
    }
    patchPluginXml {
        sinceBuild.set("223")
        untilBuild.set("233.*")
    }
}
