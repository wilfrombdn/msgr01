pluginManagement {
    repositories { gradlePluginPortal(); google(); mavenCentral(); maven("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google(); mavenCentral()
        maven("https://build-artifacts.signal.org/libraries/maven/") { content { includeGroup("org.signal") } }
    }
}
rootProject.name = "TellemDesktop"
