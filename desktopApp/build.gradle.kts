import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.material3)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.multiplatform.settings.no.arg)
    implementation(libs.logback.classic)
}

compose.desktop {
    application {
        mainClass = "com.example.traincontrol.MainKt"

        jvmArgs += listOf(
            "-Xmx96m",                 // Heap auf 96MB reduzieren
            "-Xms32m",                 // Start mit 32MB
            "-XX:MaxMetaspaceSize=64m", // Klassen-Metadaten begrenzen
            "-XX:ReservedCodeCacheSize=32m", // JIT-Cache begrenzen
            "-Xss256k",                // Stackgröße pro Thread minimieren
            "-XX:+UseSerialGC",        // Ressourcenschonender GC
            "-XX:MinHeapFreeRatio=20", // Speicher schneller an OS zurückgeben
            "-XX:MaxHeapFreeRatio=40"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "TrainControlSTmobil"
            packageVersion = "1.0.6"
            copyright = "© 2026 g.andi"
            description = "Zug-Anzeige Südtirol"
        }
    }
}