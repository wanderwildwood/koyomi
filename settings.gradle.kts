pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "koyomi"
include(":app")
// The calendar sync engine and glue, a copy of /opt/projects/shared-davsync (see davsync/README.md there).
include(":dav4jvm", ":synctools", ":davsync")
