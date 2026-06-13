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
    }
}

rootProject.name = "EmojiEnglish"

include(":app")

// core
include(":core:model")
include(":core:designsystem")
include(":core:voice")
include(":core:content")
include(":core:data")

// feature
include(":feature:step-api")
include(":feature:main")
include(":feature:home")
include(":feature:player")
include(":feature:master")

// steps — each is an independent module, assembled only by :app.
// Removing any include() here must still leave a buildable app
// (the missing type renders as an "unsupported step" card). See §0.6, §2.
include(":steps:wordcomic")
include(":steps:storycomic")
include(":steps:voiceexplain")
include(":steps:similarcard")
include(":steps:shadowing")
include(":steps:question")
include(":steps:chunk")
include(":steps:passageread")
