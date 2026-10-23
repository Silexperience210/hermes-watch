import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("app.cash.paparazzi")
}

// La clé de signature n'est JAMAIS dans le dépôt. Elle est lue dans keystore.properties (à la
// racine, ignoré par git) ou dans les variables HERMES_WATCH_KEYSTORE / _PASSWORD / _ALIAS.
// Sans elle, la version « release » est signée avec la clé de débogage : elle s'installe, mais
// ne pourra pas se mettre à jour par-dessus une version signée avec la vraie clé.
val proprietes = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun reglage(cle: String, env: String): String? = proprietes.getProperty(cle) ?: System.getenv(env)
val fichierCle = reglage("storeFile", "HERMES_WATCH_KEYSTORE")?.let { rootProject.file(it) }
val signee = fichierCle?.isFile == true

android {
    namespace = "fr.silex.hermesmontre"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.silex.hermesmontre"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "0.6.1"
    }

    signingConfigs {
        if (signee) {
            create("hermes") {
                storeFile = fichierCle
                storePassword = reglage("storePassword", "HERMES_WATCH_KEYSTORE_PASSWORD")
                keyAlias = reglage("keyAlias", "HERMES_WATCH_KEY_ALIAS") ?: "hermes-watch"
                keyPassword = reglage("keyPassword", "HERMES_WATCH_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signee) signingConfigs.getByName("hermes") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Les tests JVM touchent Handler/Log d'Android : des valeurs par défaut suffisent.
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
