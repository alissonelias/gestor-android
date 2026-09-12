import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// FCM (B1). Os valores do projeto Firebase ficam em `local.properties` (arquivo
// já ignorado pelo git) ou em variáveis de ambiente — assim nenhuma credencial
// entra no repositório. Sem eles o app compila normalmente e só não registra
// notificações (o GPS continua igual).
val firebaseProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun firebaseValue(key: String): String =
    (firebaseProps.getProperty(key) ?: System.getenv(key) ?: "").trim()

android {
    namespace = "com.gestor.comprador"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gestor.comprador"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseValue("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseValue("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseValue("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebaseValue("FIREBASE_SENDER_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Rede
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Persistência local (token / configurações)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Notificação nativa (FCM). O app é WebView: Web Push não funciona em
    // segundo plano, então a entrega real é pelo serviço do Firebase.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
