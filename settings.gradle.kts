pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "skapp"
include(":engine-core")
include(":engine-net")
include(":engine-algo")
include(":engine-runtime")
include(":engine")
include(":ui")
include(":app")
