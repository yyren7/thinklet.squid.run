pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven {
            name = "GitHub Packages"
            setUrl("https://maven.pkg.github.com/FairyDevicesRD/thinklet.app.sdk")
            credentials {
                val propertiesFile = file("local.properties")
                if (propertiesFile.exists()) {
                    val properties = java.util.Properties()
                    properties.load(propertiesFile.inputStream())
                    username = properties.getProperty("USERNAME")
                    password = properties.getProperty("TOKEN")
                }
            }
        }
    }
    versionCatalogs {
        create("thinkletLibs") {
            from(files("gradle/thinklet.versions.toml"))
        }
    }
}

rootProject.name = "thinklet.squid.run"
include(":app")
