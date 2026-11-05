# `libs/maven/` —— 仓库内置的 Maven 仓库

这里放的是**第三方 mod 库的 jar**，按标准 Maven 目录布局摆放，供 `build.gradle` 当作本地仓库解析。

## 为什么要内置，不直接连远程 maven

`MidnightLib` 只发布在 Modrinth 的 maven 上，而实测该 maven 对本项目**完全不可用**：

```
ERR 404  https://api.modrinth.com/maven/mavenmods/midnightlib/maven-metadata.xml
ERR 404  https://api.modrinth.com/maven/mavenmods/midnightlib/1.10.0/midnightlib-1.10.0.jar
ERR 404  https://api.modrinth.com/maven/mavenmods/fabric-api/maven-metadata.xml   ← 对照组，同样 404
```

连 `fabric-api` 这种主流项目都 404，说明不是坐标写错，是这个 maven 端点本身对我们不通。
而 Modrinth 的 **API 和 CDN 是通的**（`api.modrinth.com/v2/...`、`cdn.modrinth.com/data/...`），
所以 jar 从 CDN 取，再按 Maven 布局放进这里。

好处是构建和 CI 都不依赖任何第三方 maven 在线：`maven.fabricmc.net` 在这台机器上本来就极不稳定，
再挂两个不稳定的源只会让"能不能编译"变成看运气。

## 现有构件

| 坐标 | 来源 | 用途 |
|---|---|---|
| `eu.midnightdust:midnightlib:1.10.0` | Modrinth CDN，原文件 `midnightlib-fabric-1.10.0+1.21.1.jar` | `MidnightConfig`：注解式配置 + 自动生成 GUI |
| `com.terraformersmc:modmenu:11.0.5` | `maven.terraformersmc.com/releases` | 模组列表里的"配置"按钮 |

POM 是手写的最小版本，**刻意不声明任何依赖**：
midnightlib 原本依赖 `fabric-resource-loader-v0`，但 fabric-api 已经提供了它，
写进 POM 反而会让 Gradle 试图从这个本地仓库解析 Fabric 构件而失败。

## 怎么升级版本

1. 查 Modrinth API 拿到目标版本的下载地址：

   ```
   https://api.modrinth.com/v2/project/midnightlib/version?game_versions=["1.21.1"]&loaders=["fabric"]
   ```

2. 下载 jar，放进 `<group 路径>/<artifact>/<新版本>/`，文件名必须是 `<artifact>-<版本>.jar`
3. 复制一份同目录的 `.pom`，改里面的 `<version>`
4. 改根 `gradle.properties` 里的 `midnightlib_version` / `modmenu_version`
5. `./gradlew build` 验证

版本号里**不要带 `+`**（比如别写成 `1.10.0+1.21.1`）。`+` 在 URL 里要转义成 `%2B`，
Gradle 解析本地文件仓库时容易对不上路径。midnightlib 的 jar 内部 `fabric.mod.json`
声明的版本本来就是 `1.10.0`，用它更准确。

## 许可

MidnightLib 是 MIT，Mod Menu 是 MIT。两者都允许再分发，这里保留原始 jar 未作修改。
