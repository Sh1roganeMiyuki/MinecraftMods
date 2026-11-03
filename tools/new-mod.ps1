#Requires -Version 5.1
<#
.SYNOPSIS
    在 monorepo 里新建一个 mod 子模块。

.DESCRIPTION
    多模块架构下新 mod 很轻：不需要 Gradle wrapper、settings.gradle、gradle.properties、
    .gitignore、LICENSE 这些外壳，公共配置全在仓库根的 build.gradle 里。
    这个脚本只生成 mod 自己的东西，一共 7 个文件。

    生成完立刻可用，在仓库根执行：
        ./gradlew :<modid>:build

    settings.gradle 会自动发现新目录，CI 也一样，不需要任何登记动作。

.EXAMPLE
    .\tools\new-mod.ps1 -ModId spearmod -ClassName SpearMod -Description "长枪武器与相关附魔"

.EXAMPLE
    # 只给 ModId，其余全自动推导（spearmod -> SpearMod -> "SpearMod Mod"）
    .\tools\new-mod.ps1 -ModId spearmod
#>
[CmdletBinding()]
param(
	[Parameter(Mandatory = $true, Position = 0)]
	[string] $ModId,

	# 主类名。不给就从 ModId 帕斯卡化推导，spearmod -> Spearmod。
	# 想要 SpearMod 这种驼峰就显式传：-ClassName SpearMod
	[string] $ClassName,

	[string] $DisplayName,

	[string] $Description = ''
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$targetDir = Join-Path $repoRoot $ModId
$rootProps = Join-Path $repoRoot 'gradle.properties'

# ---------------------------------------------------------------- 校验

if ($ModId -notmatch '^[a-z][a-z0-9_-]{1,63}$') {
	throw "ModId '$ModId' 不合法：只能用小写字母、数字、下划线、连字符，且以字母开头"
}
if (Test-Path $targetDir) {
	throw "目标目录已存在：$targetDir"
}
if (-not (Test-Path (Join-Path $repoRoot 'settings.gradle'))) {
	throw "仓库根没有 settings.gradle，这个脚本只能在多模块仓库里用"
}
if (-not (Test-Path $rootProps)) {
	throw "仓库根没有 gradle.properties，读不到版本号"
}

# ---------------------------------------------------------------- 从根配置继承

$props = @{}
foreach ($line in Get-Content $rootProps) {
	if ($line -match '^\s*([A-Za-z0-9_.]+)\s*=\s*(.*)$') {
		$props[$matches[1]] = $matches[2].Trim()
	}
}
$groupBase = if ($props.ContainsKey('mod_group_base')) { $props['mod_group_base'] } else { 'com.example' }
$mcVersion = if ($props.ContainsKey('minecraft_version')) { $props['minecraft_version'] } else { '?' }

# ---------------------------------------------------------------- 派生命名

# 包名段不能带连字符，spear-mod -> spearmod
$segment = ($ModId -replace '[-_]', '')

if (-not $ClassName) {
	# 类名默认走帕斯卡：spear-mod -> SpearMod，spearmod -> Spearmod
	$ClassName = (($ModId -split '[-_]') | ForEach-Object {
			if ($_.Length -gt 0) { $_.Substring(0, 1).ToUpper() + $_.Substring(1) }
		}) -join ''
}
if ($ClassName -notmatch '^[A-Z][A-Za-z0-9_]*$') {
	throw "ClassName '$ClassName' 不是合法的 Java 类名（要求大写字母开头，只能含字母数字下划线）"
}
if (-not $DisplayName) { $DisplayName = "$ClassName Mod" }
if ($Description -eq '') { $Description = "$DisplayName —— Minecraft Fabric 小玩法模组。" }
if ($Description -match '["\\]' -or $DisplayName -match '["\\]') {
	throw 'DisplayName / Description 不能包含双引号或反斜杠，否则会破坏 fabric.mod.json'
}

$rootPackage = "$groupBase.$segment"
$packagePath = $rootPackage -replace '\.', '/'

Write-Host "新 mod   : $ModId" -ForegroundColor Cyan
Write-Host "主类     : $rootPackage.$ClassName"
Write-Host "MC 版本  : $mcVersion（继承自根 gradle.properties）"
Write-Host "目录     : $targetDir"

# ---------------------------------------------------------------- 写文件

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$replacements = @{
	'__MOD_ID__'       = $ModId
	'__CLASS_NAME__'   = $ClassName
	'__ROOT_PACKAGE__' = $rootPackage
	'__DISPLAY_NAME__' = $DisplayName
	'__DESCRIPTION__'  = $Description
	'__MC_VERSION__'   = $mcVersion
	'__JAVA_HOME__'    = $props['org.gradle.java.home']
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
	$text = $text -replace "`r`n", "`n"
	[IO.File]::WriteAllText($full, $text, $utf8NoBom)
	Write-Host "  + $RelPath"
}

New-Item -ItemType Directory -Path $targetDir -Force | Out-Null

# settings.gradle 靠这个文件识别子模块，所以它必须存在，哪怕是空的
Write-Tpl 'build.gradle' @'
// 公共配置（Loom、Minecraft/Fabric 依赖、仓库源与排序、Java 21、打包、发布、group 派生）
// 全在仓库根 build.gradle 的 subprojects 块里。
//
// 这个文件目前没有内容，但它必须存在——settings.gradle 靠它识别子模块。
//
// 需要本 mod 独有的依赖时这样加：
// dependencies {
//     modImplementation "some.other:mod:x.y.z"
// }
'@

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
		"fabricloader": ">=0.20.1",
		"minecraft": "~__MC_VERSION__",
		"java": ">=21",
		"fabric-api": "*"
	}
}
'@

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

Write-Tpl 'README.md' @'
# __DISPLAY_NAME__

__DESCRIPTION__

## 构建

在**仓库根**执行（不是在 mod 目录里，本模块没有自己的 gradlew）：

```powershell
$env:JAVA_HOME = "__JAVA_HOME__"
./gradlew :__MOD_ID__:build
```

产物在 `build/libs/__MOD_ID__-<version>.jar`，不要用带 `-sources` 的那个。

## 开发运行

```powershell
$env:JAVA_HOME = "__JAVA_HOME__"
./gradlew :__MOD_ID__:runClient
```
'@

Write-Tpl 'AGENTS.md' @'
# __DISPLAY_NAME__ 项目说明

## 项目目标

- __DESCRIPTION__

## 环境事实

- 这是 MinecraftMods monorepo 的一个**子模块**，不是独立 Gradle 项目
- 公共构建配置（Loom、依赖、仓库源与排序、Java 21、打包、发布、group 派生）
  全在 `../build.gradle` 的 `subprojects` 块里
- 版本号、JDK 路径在 `../gradle.properties`；Loom 插件版本在 `../settings.gradle`
- 仓库级的环境事实、构建命令、已知的坑都在 `../AGENTS.md`，**动手前先读那个**
- 模组 ID：`__MOD_ID__`；根包：`__ROOT_PACKAGE__`
- 本模块没有、也不该有自己的 gradlew / settings.gradle / gradle.properties /
  .gitignore / LICENSE，那些都是仓库级共享的

## 常用命令

一律在**仓库根**执行：

- 只构建本 mod：`./gradlew :__MOD_ID__:build`
- 构建全部 mod：`./gradlew build`
- 本 mod 的开发客户端：`./gradlew :__MOD_ID__:runClient`
- 产物：`__MOD_ID__/build/libs/__MOD_ID__-<version>.jar`

本机没有系统 JDK，PowerShell 里先设：

```powershell
$env:JAVA_HOME = "__JAVA_HOME__"
```

## 编码约定

- 只用 Fabric API 和 Mojang 映射下的官方类；禁止 Forge / NeoForge API
- 伤害、击退、附魔触发等玩法逻辑必须在服务端执行；客户端只负责渲染、粒子、音效
- 客户端专用类放 `src/client/java`，不要被服务端代码引用
- 注册在 `__CLASS_NAME__.onInitialize` 中完成
- 资源路径必须与注册 ID 对齐：`assets/__MOD_ID__/...`、`data/__MOD_ID__/...`
- 不要修改 `build/`、`.gradle/`、`run/` 下的内容
- Mixin 清单在 `src/main/resources/__MOD_ID__.mixins.json`，类放 `__ROOT_PACKAGE__.mixin`。
  refmap 由根 build.gradle 的 `useLegacyMixinAp = true` 保证生成，别在子模块里重复配置
- 需要本 mod 独有的依赖或仓库源，写进本目录的 `build.gradle`，不要去改根配置

## 工作方式

- 一次只实现一个功能，改完立即 `./gradlew :__MOD_ID__:build`，编译通过再继续
- 不确定 API 时先核实，不要凭记忆猜类名或方法名（本机没有 Minecraft 源码 jar，
  用 javap 查 Loom 的 mapped jar，具体命令见 `../AGENTS.md`）
- 优先使用 Fabric API 事件；没有对应事件时才用 Mixin
- `build` 通过只代表编译打包成功，游戏内行为仍需 `runClient` / `runServer` 验证
'@

# ---------------------------------------------------------------- 图标

$iconSrc = Join-Path $repoRoot 'funmod\src\main\resources\assets\funmod\icon.png'
if (Test-Path $iconSrc) {
	$iconDstDir = Join-Path $targetDir "src\main\resources\assets\$ModId"
	New-Item -ItemType Directory -Path $iconDstDir -Force | Out-Null
	Copy-Item $iconSrc (Join-Path $iconDstDir 'icon.png') -Force
	Write-Host "  + assets/$ModId/icon.png（从 funmod 拷的占位图，记得换）"
} else {
	Write-Warning "找不到 $iconSrc，跳过图标。fabric.mod.json 里的 icon 字段会指向不存在的文件"
}

Write-Host ""
Write-Host "完成。在仓库根构建：" -ForegroundColor Green
Write-Host "  `$env:JAVA_HOME = `"$($props['org.gradle.java.home'])`""
Write-Host "  ./gradlew :${ModId}:build"
