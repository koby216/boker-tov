plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.koby.bokertov"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.koby.bokertov"
        minSdk = 26
        targetSdk = 36
        // כל העלאה לחנות צריכה מספר גדול יותר: משתמשים במספר הבנייה של ה-CI
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1"
        // מספר הגרסה (חלק מהמזהה של ההעלאה) מוצג במסך הראשי, כדי לדעת איזו גרסה מותקנת
        buildConfigField("String", "BUILD_ID", "\"${System.getenv("GITHUB_SHA")?.take(7) ?: "local"}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // שתי גרסאות: direct (קובץ התקנה רגיל, עם כפתור עדכון) ו-play (לחנות, בלי עדכון עצמי והסרה)
    flavorDimensions += "dist"
    productFlavors {
        create("direct") {
            dimension = "dist"
            buildConfigField("boolean", "PLAY", "false")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "PLAY", "true")
        }
    }

    // מפתח חתימה קבוע, כדי שכל גרסה חדשה תתעדכן מעל הקודמת בלי להתנגש
    signingConfigs {
        // מפתח העלאה לחנות Play. הוא לא נשמר בקוד: ה-CI מקבל אותו מסודות (secrets) בגיטהאב.
        val uploadFile = System.getenv("UPLOAD_KEYSTORE_FILE")
        if (uploadFile != null && file(uploadFile).exists()) {
            create("upload") {
                storeFile = file(uploadFile)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = "upload"
                keyPassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // אם יש מפתח העלאה (בענן, מהסודות), חותמים בו. בלעדיו הקובץ נשאר לא חתום.
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // זיהוי נסיעה (שירות התנועה של גוגל)
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
