pluginManagement {
    repositories {
        // Android Gradle Plugin and Android/Google build plugins.
        maven {
            name = "GoogleDirectPlugins"
            url = uri("https://dl.google.com/dl/android/maven2/")
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
            }
        }
        // Kotlin and other JVM/Gradle plugins.
        maven {
            name = "MavenCentralDirectPlugins"
            url = uri("https://repo.maven.apache.org/maven2/")
        }
        gradlePluginPortal()

        // Code On the Go / AndroidIDE cache is a LAST fallback only.
        // Keeping it last prevents incomplete cached modules from shadowing
        // complete artifacts available from the Internet repositories above.
        val androidIdeLocalRepo = file("/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository")
        if (androidIdeLocalRepo.exists()) {
            maven {
                name = "AndroidIdeLocalFallbackPlugins"
                url = uri(androidIdeLocalRepo)
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Artifacts maintained in Google's Android Maven repository.
        // IMPORTANT: do NOT route all com.google.* here: Guava and many JVM
        // libraries live in Maven Central, not Google Maven.
        exclusiveContent {
            forRepository {
                maven {
                    name = "GoogleDirect"
                    url = uri("https://dl.google.com/dl/android/maven2/")
                }
            }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.firebase(\\..*)?")
                includeGroupByRegex("com\\.google\\.mlkit(\\..*)?")
            }
        }

        // Guava (com.google.guava), Kotlin/Coroutines, JUnit and normal JVM
        // dependencies are resolved here.
        maven {
            name = "MavenCentralDirect"
            url = uri("https://repo.maven.apache.org/maven2/")
            mavenContent { releasesOnly() }
        }

        // Use Code On the Go's preloaded Maven cache only when an artifact is
        // unavailable online. It is intentionally LAST to avoid the incomplete
        // AAR/JAR entries that caused processDebugNavigationResources failures.
        val androidIdeLocalRepo = file("/data/data/com.itsaky.androidide/files/home/maven/localMvnRepository")
        if (androidIdeLocalRepo.exists()) {
            maven {
                name = "AndroidIdeLocalFallback"
                url = uri(androidIdeLocalRepo)
            }
        }
    }
}

rootProject.name = "TransVibe"
include(":app")
