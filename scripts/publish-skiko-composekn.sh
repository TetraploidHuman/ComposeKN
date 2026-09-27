#!/usr/bin/env bash
# 把 ComposeKN 用的 Skiko 发成 com.composekn:skiko:<composekn.version>。
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CKN_VER="$(grep '^composekn.version=' "$REPO/gradle.properties" | cut -d= -f2)"
: "${GITHUB_TOKEN:?GITHUB_TOKEN required}"
: "${GITHUB_ACTOR:=github}"
OWNER="${GITHUB_REPOSITORY_OWNER:-TetraploidHuman}"
GH_REPO_NAME="${GITHUB_REPOSITORY##*/}"
GH_REPO_NAME="${GH_REPO_NAME:-ComposeKN}"

info() { printf '\033[36m==>\033[0m %s\n' "$1"; }
info "Publishing com.composekn:skiko:$CKN_VER"

INIT="$(mktemp)"
cat > "$INIT" <<EOF
gradle.projectsLoaded {
    rootProject.allprojects { p ->
        p.pluginManager.withPlugin("maven-publish") {
            p.extensions.configure(org.gradle.api.publish.PublishingExtension) { pub ->
                pub.publications.withType(org.gradle.api.publish.maven.MavenPublication).configureEach {
                    groupId = "com.composekn"
                    // 保留各 publication 自己的 artifactId（kotlinMultiplatform / linuxX64 等）
                    version = "$CKN_VER"
                }
                pub.repositories.maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$OWNER/$GH_REPO_NAME")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: "github"
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
                pub.repositories.maven {
                    name = "ComposeKnLocal"
                    url = uri(new File("$REPO/build/maven-repo"))
                }
            }
        }
    }
}
EOF

cd "$REPO/vendor/skiko/skiko"
# 只发 KMP 元数据 + native；跳过 JVM/Android 等与 ComposeKN 无关的 publication（失败可忽略单任务）
./gradlew --init-script "$INIT" \
  -Pdeploy.version="$CKN_VER" \
  publishKotlinMultiplatformPublicationToGitHubPackagesRepository \
  publishLinuxX64PublicationToGitHubPackagesRepository \
  publishMingwX64PublicationToGitHubPackagesRepository \
  publishKotlinMultiplatformPublicationToComposeKnLocalRepository \
  publishLinuxX64PublicationToComposeKnLocalRepository \
  publishMingwX64PublicationToComposeKnLocalRepository \
  --continue --no-daemon

rm -f "$INIT"
info "done (check $REPO/build/maven-repo/com/composekn/skiko)"
