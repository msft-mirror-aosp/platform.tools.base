androidLibrary {
    namespace = "org.example.mylib"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

//        proguardFile(getDefaultProguardFile("proguard-android.txt"))
//        proguardFile("missing.txt")

        buildConfigField("String", "MY_STRING1", """ "myValue" """)
        buildConfigField("String", "MY_STRING2", "\"myValue\"")
        buildConfigField("Integer", "MY_INT", "0")
    }

    compileOptions {
        encoding = "utf-8"
//        sourceCompatibility = VERSION_17
//        targetCompatibility = VERSION_17
    }

//    buildTypes {
//        buildType("debug") {
//            //isMinifyEnabled = false
//        }
//
//        buildType("release") {
//            //isMinifyEnabled = true
//        }
//
//        buildType("benchmark") {
//            //isMinifyEnabled = false
//        }
//    }

//    sourceSets {
//        configure("main") {
//            kotlin {
//                srcDir("src/main/kotlin")
//                srcDir("src/main/other/kotlin")
//            }
//        }
//    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    dependenciesDcl {
        implementation("org.apache.commons:commons-lang3:3.13.0")
        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
        implementation("com.google.guava:guava:32.0.1-jre")
        testImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
        androidTestImplementation("androidx.test.ext:junit:1.1.2")
        androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    }
}
