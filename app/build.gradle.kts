plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "fr.maxence.gpsperso"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.maxence.gpsperso"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // Clé Lufop injectée depuis le secret GitHub LUFOP_KEY (jamais écrite dans le code)
        buildConfigField("String", "LUFOP_KEY", "\"${System.getenv("LUFOP_KEY") ?: ""}\"")
        buildConfigField("String", "GOOGLE_KEY", "\"${System.getenv("GOOGLE_KEY") ?: ""}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Clé de signature fixe : permet d'installer chaque nouvelle APK par-dessus l'ancienne.
    // Usage strictement personnel, d'où le mot de passe en clair.
    signingConfigs {
        create("perso") {
            storeFile = file("gps-perso.jks")
            storePassword = "gpsperso"
            keyAlias = "gpsperso"
            keyPassword = "gpsperso"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("perso")
        }
        debug {
            signingConfig = signingConfigs.getByName("perso")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.core:core:1.13.1")
}
