#Requires -Version 5.1
<#
.SYNOPSIS
    一键生成一个新的 Fabric mod 项目。

.DESCRIPTION
    以仓库里已有的 mod（默认 funmod）为模板，生成一个结构完整、可直接 ./gradlew build 的
    新项目。版本号、JDK 路径、Gradle wrapper 都从模板继承，不会各 mod 之间漂移。

    刻意不复制 .maven-local（约 20MB）：Fabric 的构件已经在 ~/.gradle/caches/modules-2 里，
    Minecraft 本体和映射在 ~/.gradle/caches/fabric-loom 里，都是全局共享的。
    真的需要离线引导时加 -WithBootstrap。

    生成的项目不含任何需要改根目录文件的东西——CI 会自动发现它。

.EXAMPLE
    .\tools\new-mod.ps1 -ModId spearmod -Description "长枪武器与相关附魔"

.EXAMPLE
    .\tools\new-mod.ps1 -ModId gate-mod -DisplayName "Gate Mod" -WithBootstrap
#>
[CmdletBinding()]
param(
	[Parameter(Mandatory = $true, Position = 0)]
	[string] $ModId,

	[string] $DisplayName,

	# 主类名。不给就从 ModId 帕斯卡化推导，spearmod -> Spearmod。
	# 想要 SpearMod 这种驼峰就显式传：-ClassName SpearMod
	[string] $ClassName,

	[string] $Description = '',

	[string] $PackageBase = 'com.example',

	[string] $Template = 'funmod',

	[switch] $WithBootstrap
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- 路径与校验

$repoRoot = Split-Path -Parent $PSScriptRoot
$templateDir = Join-Path $repoRoot $Template
$targetDir = Join-Path $repoRoot $ModId

if ($ModId -notmatch '^[a-z][a-z0-9_-]{1,63}$') {
	throw "ModId '$ModId' 不合法：只能用小写字母、数字、下划线、连字符，且以字母开头"
}
if (-not (Test-Path $templateDir)) {
	throw "找不到模板项目：$templateDir"
}
if (Test-Path $targetDir) {
	throw "目标目录已存在：$targetDir"
}
if ($PackageBase -notmatch '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)*$') {
	throw "PackageBase '$PackageBase' 不是合法的 Java 包名前缀"
}
if ($Description -match '["\\]' -or $DisplayName -match '["\\]') {
	throw 'DisplayName / Description 不能包含双引号或反斜杠，否则会破坏 fabric.mod.json'
}
if ($ClassName -and $ClassName -notmatch '^[A-Z][A-Za-z0-9_]*$') {
	throw "ClassName '$ClassName' 不是合法的 Java 类名（要求大写字母开头）"
}

# ---------------------------------------------------------------- 派生命名

# 包名段不能带连字符，spear-mod -> spearmod
$segment = ($ModId -replace '[-_]', '')
# 类名默认走帕斯卡：spear-mod -> SpearMod，spearmod -> Spearmod
if (-not $ClassName) {
	$ClassName = (($ModId -split '[-_]') | ForEach-Object {
			if ($_.Length -gt 0) { $_.Substring(0, 1).ToUpper() + $_.Substring(1) }
		}) -join ''
}
$rootPackage = "$PackageBase.$segment"
$packagePath = $rootPackage -replace '\.', '/'

if (-not $DisplayName) { $DisplayName = "$ClassName Mod" }
if ($Description -eq '') { $Description = "$DisplayName —— Minecraft Fabric 小玩法模组。" }

# ---------------------------------------------------------------- 继承模板的版本号
$props = @{}
foreach ($line in Get-Content (Join-Path $templateDir 'gradle.properties')) {
	if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$') {
		$props[$matches[1]] = $matches[2].Trim()
	}
}
foreach ($key in @('minecraft_version', 'loader_version', 'loom_version', 'fabric_api_version', 'org.gradle.java.home')) {
	if (-not $props.ContainsKey($key)) { throw "模板 gradle.properties 缺少 $key，无法继承版本" }
}

Write-Host "新项目   : $ModId" -ForegroundColor Cyan
Write-Host "主类     : $rootPackage.$className"
Write-Host "MC/Loom  : $($props['minecraft_version']) / $($props['loom_version'])"
Write-Host "目标目录 : $targetDir"

# ---------------------------------------------------------------- 写文件工具

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# 占位符之间不存在包含关系，所以遍历顺序无关紧要
$replacements = @{
	'__MOD_ID__'             = $ModId
	'__CLASS_NAME__'         = $ClassName
	'__ROOT_PACKAGE__'       = $rootPackage
	'__DISPLAY_NAME__'       = $DisplayName
	'__DESCRIPTION__'        = $Description
	'__MC_VERSION__'         = $props['minecraft_version']
	'__LOADER_VERSION__'     = $props['loader_version']
	'__LOOM_VERSION__'       = $props['loom_version']
	'__FABRIC_API_VERSION__' = $props['fabric_api_version']
	'__JAVA_HOME__'          = $props['org.gradle.java.home']
}

function Write-Tpl {
	param([string] $RelPath, [string] $Content)

	$full = Join-Path $targetDir $RelPath
	$dir = Split-Path -Parent $full
	if ($dir -and -not (Test-Path $dir)) {
		New-Item -ItemType Directory -Path $dir -Force | Out-Null
	}
	# PowerShell 不允许换行后以 .Method() 开头，所以用循环而不是链式调用
	$text = $Content
	foreach ($key in $replacements.Keys) {
		$text = $text.Replace($key, [string] $replacements[$key])
	}
	# 统一换行，避免 Windows 上生成一半 CRLF 一半 LF
	$text = $text -replace "`r`n", "`n"
	[IO.File]::WriteAllText($full, $text, $utf8NoBom)
	Write-Host "  + $RelPath"
}

New-Item -ItemType Directory -Path $targetDir -Force | Out-Null

# ---------------------------------------------------------------- settings.gradle

Write-Tpl 'settings.gradle' @'
pluginManagement {
	// 只有在本项目放了 .maven-local 时才启用，新项目默认靠全局 Gradle 缓存
	def bootstrapRepo = new File(rootDir, '.maven-local')
	repositories {
		if (bootstrapRepo.exists()) {
			maven {
				name = 'Bootstrap Local'
				url = bootstrapRepo.toURI()
			}
		}
		maven {
			name = 'Aliyun Public'
			url = 'https://maven.aliyun.com/repository/public'
		}
		maven {
			name = 'Fabric Mirror'
			url = 'https://repository.hanbings.io/proxy'
		}
		maven {
			name = 'Fabric'
			url = 'https://maven.fabricmc.net/'
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

rootProject.name = '__MOD_ID__'
'@

# ---------------------------------------------------------------- build.gradle

Write-Tpl 'build.gradle' @'
plugins {
	id 'net.fabricmc.fabric-loom-remap' version "__LOOM_VERSION__"
	id 'maven-publish'
}

repositories {
	maven {
		name = 'Bootstrap Local'
		url = uri('.maven-local')
	}
	maven {
		name = 'Aliyun Public'
		url = 'https://maven.aliyun.com/repository/public'
	}
	maven {
		name = 'Fabric Mirror'
		url = 'https://repository.hanbings.io/proxy'
	}
}

// Loom 会在这个脚本之前注册官方 Fabric 源。
// 把引导仓库提到最前，优先命中本地已预取的构件。
def bootstrapRepo = repositories.findByName('Bootstrap Local')
if (bootstrapRepo != null) {
	repositories.remove(bootstrapRepo)
	repositories.add(0, bootstrapRepo)
}

// 官方 Fabric 源在本机极不稳定，压到最后，并且只允许它提供 Fabric 自己的构件。
def fabricRepo = repositories.findByName('Fabric')
if (fabricRepo != null) {
	repositories.remove(fabricRepo)
	repositories.add(fabricRepo)
	fabricRepo.content {
		includeGroupByRegex 'net\\.fabricmc.*'
	}
}

loom {
	splitEnvironmentSourceSets()

	// Loom 1.19 默认不挂 Mixin 注解处理器，必须显式打开。
	// 缺了它不会生成 refmap：开发环境一切正常，打包后 Mixin 在生产环境静默失效。
	// 这行不要删。
	mixin {
		useLegacyMixinAp = true
	}

	mods {
		"__MOD_ID__" {
			sourceSet sourceSets.main
			sourceSet sourceSets.client
		}
	}
}

dependencies {
	minecraft "com.mojang:minecraft:${project.minecraft_version}"
	mappings loom.officialMojangMappings()
	modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
	modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}

processResources {
	def version = project.version
	inputs.property "version", version

	filesMatching("fabric.mod.json") {
		expand "version": version
	}
}

tasks.withType(JavaCompile).configureEach {
	it.options.release = 21
}

java {
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_21
	targetCompatibility = JavaVersion.VERSION_21
}

jar {
	def projectName = project.name
	inputs.property "projectName", projectName

	from("LICENSE") {
		rename { "${it}_$projectName" }
	}
}

publishing {
	publications {
		create("mavenJava", MavenPublication) {
			from components.java
		}
	}
	repositories {
	}
}
'@

# ---------------------------------------------------------------- gradle.properties

Write-Tpl 'gradle.properties' @'
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
org.gradle.configuration-cache=true
systemProp.org.gradle.internal.http.connectionTimeout=120000
systemProp.org.gradle.internal.http.socketTimeout=120000

# 本机没有系统 JDK，也没配 JAVA_HOME。Loom 1.19 要求 Gradle 跑在 JDK 25 上，
# 游戏代码仍然编译到 Java 21。CI 上用 -Dorg.gradle.java.home 覆盖这一行。
org.gradle.java.home=__JAVA_HOME__

# Fabric Properties  https://fabricmc.net/develop
minecraft_version=__MC_VERSION__
loader_version=__LOADER_VERSION__
loom_version=__LOOM_VERSION__

# Mod Properties
version=0.1.0
group=__ROOT_PACKAGE__

# Dependencies
fabric_api_version=__FABRIC_API_VERSION__
'@

# ---------------------------------------------------------------- fabric.mod.json

Write-Tpl 'src/main/resources/fabric.mod.json' @'
{
	"schemaVersion": 1,
	"id": "__MOD_ID__",
	"version": "${version}",
	"name": "__DISPLAY_NAME__",
	"description": "__DESCRIPTION__",
	"license": "CC0-1.0",
	"icon": "assets/__MOD_ID__/icon.png",
	"environment": "*",
	"entrypoints": {
		"main": [
			"__ROOT_PACKAGE__.__CLASS_NAME__"
		],
		"client": [
			"__ROOT_PACKAGE__.client.__CLASS_NAME__Client"
		]
	},
	"mixins": [
		"__MOD_ID__.mixins.json"
	],
	"depends": {
		"fabricloader": ">=__LOADER_VERSION__",
		"minecraft": "~__MC_VERSION__",
		"java": ">=21",
		"fabric-api": "*"
	}
}
'@

# ---------------------------------------------------------------- mixin 清单

Write-Tpl "src/main/resources/$ModId.mixins.json" @'
{
	"required": true,
	"package": "__ROOT_PACKAGE__.mixin",
	"compatibilityLevel": "JAVA_21",
	"mixins": [],
	"client": [],
	"injectors": {
		"defaultRequire": 1
	}
}
'@

# ---------------------------------------------------------------- 主类

Write-Tpl "src/main/java/$packagePath/$ClassName.java" @'
package __ROOT_PACKAGE__;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class __CLASS_NAME__ implements ModInitializer {
	public static final String MOD_ID = "__MOD_ID__";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("__DISPLAY_NAME__ loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
'@

Write-Tpl "src/client/java/$packagePath/client/${ClassName}Client.java" @'
package __ROOT_PACKAGE__.client;

import net.fabricmc.api.ClientModInitializer;

public class __CLASS_NAME__Client implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
	}
}
'@

# ---------------------------------------------------------------- README / AGENTS

Write-Tpl 'README.md' @'
# __DISPLAY_NAME__

__DESCRIPTION__

## 构建

本机没有系统 Java，先指定已安装的 JDK：

```bash
export JAVA_HOME="__JAVA_HOME__"
./gradlew build
```

产物在 `build/libs/__MOD_ID__-<version>.jar`（不要用带 `-sources` 的那个）。

## 开发运行

```bash
export JAVA_HOME="__JAVA_HOME__"
./gradlew runClient
```

## 联机

服务端和每个客户端都需要安装 Fabric Loader、Fabric API 和本模组，版本保持一致。
'@

Write-Tpl 'AGENTS.md' @'
# __DISPLAY_NAME__ 项目说明

## 项目目标

- __DESCRIPTION__

## 环境事实

- Minecraft __MC_VERSION__，Fabric
- Fabric Loader __LOADER_VERSION__，Fabric API __FABRIC_API_VERSION__
- Fabric Loom __LOOM_VERSION__，Gradle wrapper 已内置
- 游戏代码编译目标 Java 21；Gradle 本身跑在 JDK 25（Loom 1.19 的硬性要求）
- 本机没有系统 JDK，也没配 JAVA_HOME，JDK 在：`__JAVA_HOME__`
- 因此命令要这样跑：`JAVA_HOME="__JAVA_HOME__" ./gradlew build`
- 映射：Mojang 官方映射（`loom.officialMojangMappings()`），不是 Yarn
- 拆分源集：公共代码在 `src/main/java`，客户端专用代码在 `src/client/java`
- 模组 ID：`__MOD_ID__`；根包：`__ROOT_PACKAGE__`
- 依赖不需要 `.maven-local`：Fabric 构件已在 `~/.gradle/caches/modules-2`，
  Minecraft 本体与映射在 `~/.gradle/caches/fabric-loom`，都是全局共享的

## 常用命令

- 编译打包：`JAVA_HOME="__JAVA_HOME__" ./gradlew build`
- 开发客户端：`JAVA_HOME="__JAVA_HOME__" ./gradlew runClient`
- 开发服务端：`JAVA_HOME="__JAVA_HOME__" ./gradlew runServer`
- 产物：`build/libs/__MOD_ID__-<version>.jar`

## 编码约定

- 只用 Fabric API 和 Mojang 映射下的官方类；禁止 Forge / NeoForge API
- 伤害、击退、附魔触发等玩法逻辑必须在服务端执行；客户端只负责渲染、粒子、音效
- 客户端专用类放 `src/client/java`，不要被服务端代码引用
- 注册在 `__CLASS_NAME__.onInitialize` 中完成
- 资源路径必须与注册 ID 对齐：`assets/__MOD_ID__/...`、`data/__MOD_ID__/...`
- 不要修改 `build/`、`.gradle/`、`run/` 下的内容
- Mixin 清单在 `src/main/resources/__MOD_ID__.mixins.json`，类放 `__ROOT_PACKAGE__.mixin`
- `build.gradle` 里的 `useLegacyMixinAp = true` **不能删**，否则不生成 refmap，
  开发环境正常但打包后 Mixin 全部失效

## 工作方式

- 一次只实现一个功能，改完立即 `./gradlew build`，编译通过再继续
- 不确定 API 时先核实，不要凭记忆猜类名或方法名。本机没有 Minecraft 源码 jar，
  用 javap 查 Loom 的 mapped jar：
  `__JAVA_HOME__/bin/javap.exe -cp <jar> <类全名>`，
  jar 在 `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/` 下的
  `minecraft-common-*`（客户端类在 `minecraft-clientonly-*`）
- 优先使用 Fabric API 事件；没有对应事件时才用 Mixin
- `build` 通过只代表编译打包成功，游戏内行为仍需 `runClient` / `runServer` 验证

## 多 agent 协作

- 本目录只属于 `__MOD_ID__`，不要改动其他 mod 的目录
- 不要改仓库根的 `.github/workflows/build.yml`：它会自动发现所有含
  `settings.gradle` 的一级子目录，新增 mod 无需登记
- 在自己的 worktree + 分支里工作，提交前先 `git pull --rebase origin main`
'@

# ---------------------------------------------------------------- 复制二进制与通用文件

foreach ($rel in @('gradlew', 'gradlew.bat', 'gradle', '.gitignore', '.gitattributes', 'LICENSE')) {
	$src = Join-Path $templateDir $rel
	if (Test-Path $src) {
		$dst = Join-Path $targetDir $rel
		if ((Get-Item $src).PSIsContainer) {
			Copy-Item $src $dst -Recurse -Force
		} else {
			Copy-Item $src $dst -Force
		}
		Write-Host "  + $rel (copied)"
	} else {
		Write-Warning "模板缺少 $rel，已跳过"
	}
}

# 图标：先沿用模板的，之后自己换
$iconSrc = Join-Path $templateDir 'src/main/resources/assets/funmod/icon.png'
$iconDstDir = Join-Path $targetDir "src/main/resources/assets/$ModId"
if (Test-Path $iconSrc) {
	New-Item -ItemType Directory -Path $iconDstDir -Force | Out-Null
	Copy-Item $iconSrc (Join-Path $iconDstDir 'icon.png') -Force
	Write-Host "  + assets/$ModId/icon.png (copied from template, 记得换)"
}

if ($WithBootstrap) {
	$bootSrc = Join-Path $templateDir '.maven-local'
	if (Test-Path $bootSrc) {
		Write-Host "  复制 .maven-local（约 20MB，请稍候）..."
		Copy-Item $bootSrc (Join-Path $targetDir '.maven-local') -Recurse -Force
	}
}

Write-Host ""
Write-Host "完成。下一步：" -ForegroundColor Green
Write-Host "  cd $ModId"
Write-Host "  `$env:JAVA_HOME=`"$($props['org.gradle.java.home'])`"; ./gradlew build"
Write-Host ""
Write-Host "记得改：fabric.mod.json 的 description、assets/$ModId/icon.png" -ForegroundColor Yellow
