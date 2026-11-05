#Requires -Version 5.1
<#
.SYNOPSIS
    在 monorepo 里新建一个 mod 子模块。

.DESCRIPTION
    多模块架构下新 mod 很轻：不需要 Gradle wrapper、settings.gradle、gradle.properties、
    .gitignore、LICENSE 这些外壳，公共配置全在仓库根的 build.gradle 里。
    这个脚本只生成 mod 自己的东西，一共 9 个文件。

    生成的 mod **自带游戏内配置界面**（MidnightConfig + Mod Menu）：一个配置类、
    一份中文翻译、build.gradle 里一行 apply、主类里一行 register，都已经写好了。
    往配置类里加字段就能在「模组列表 → 本 mod → 配置」里拖滑条，改完立即生效。
    约定和坑见生成的 AGENTS.md，以及仓库根的 AGENTS.md。

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
$configClass = "${ClassName}Config"

Write-Host "新 mod   : $ModId" -ForegroundColor Cyan
Write-Host "主类     : $rootPackage.$ClassName"
Write-Host "配置类   : $rootPackage.$configClass（游戏内可拖滑条）"
Write-Host "MC 版本  : $mcVersion（继承自根 gradle.properties）"
Write-Host "目录     : $targetDir"

# ---------------------------------------------------------------- 写文件

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$replacements = @{
	'__MOD_ID__'       = $ModId
	'__CLASS_NAME__'   = $ClassName
	'__CONFIG_CLASS__' = $configClass
	'__ROOT_PACKAGE__' = $rootPackage
	'__ROOT_PACKAGE_PATH__' = $packagePath
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
// 这个文件即使什么都不写也必须存在——settings.gradle 靠它识别子模块。

// 游戏内配置界面（MidnightConfig + Mod Menu）。
// 依赖坐标和版本都在共享脚本里，所以升级 MidnightLib 不需要改这个文件。
apply from: rootProject.file('gradle/config-gui.gradle')

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
		"fabric-api": "*",
		"midnightlib": ">=1.10.0"
	},
	"suggests": {
		"modmenu": ">=11.0.0"
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

import eu.midnightdust.lib.config.MidnightConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class __CLASS_NAME__ implements ModInitializer {
	public static final String MOD_ID = "__MOD_ID__";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 游戏内配置界面。登记之后 MidnightLib 自带的 modmenu 入口点会自动
		// 给本 mod 挂上"配置"按钮，不需要写 ModMenuApi 实现。
		//
		// 必须在检查 enabled 之前登记，否则一旦在配置里把 mod 关掉，
		// 下次启动就再也打不开配置界面、也没法把它开回来了。
		MidnightConfig.register(MOD_ID, __CONFIG_CLASS__.class);

		if (!__CONFIG_CLASS__.enabled) {
			LOGGER.info("__DISPLAY_NAME__ 已在配置里禁用");
			return;
		}

		// 在这里注册物品、方块、事件、命令
		LOGGER.info("__DISPLAY_NAME__ loaded");
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
'@

# 配置类。只给一个真的会被读的总开关，刷一堆没人读的字段反而有害：
# 界面上拖了没反应，比没这个滑条更让人困惑
Write-Tpl "src/main/java/$packagePath/$configClass.java" @'
package __ROOT_PACKAGE__;

import eu.midnightdust.lib.config.MidnightConfig;

/**
 * __DISPLAY_NAME__ 的可调参数，游戏里在「模组列表 → __DISPLAY_NAME__ → 配置」调。
 *
 * <h2>约定</h2>
 *
 * <ul>
 *   <li><b>一个 mod 只能有一个配置类。</b>{@code MidnightConfig.configInstances} 按 modid 索引，
 *       同一个 modid 注册第二个会顶掉第一个。加新功能就往这里加字段、
 *       用 {@code category} 分新的标签页，不要再建配置类</li>
 *   <li>字段必须 {@code public static}（MidnightConfig 的约定）。游戏逻辑直接读它们，
 *       界面点"完成"时写回并存盘，所以<b>改完立即生效</b>，不需 reload 也不需重启</li>
 *   <li>跨线程读的字段加 {@code volatile}：界面在客户端线程写、玩法逻辑在服务端线程读，
 *       不加会有可见性问题，而且 JLS 不保证 double/float 的写入是原子的</li>
 *   <li><b>没代码读的字段不要加。</b>界面上拖了什么都不会发生，比没这个滑条更糟</li>
 * </ul>
 *
 * <h2>加了字段要补翻译键</h2>
 *
 * 在 {@code assets/__MOD_ID__/lang/zh_cn.json} 里补，缺了界面会直接把键名显示出来：
 *
 * <ul>
 *   <li>条目名 {@code __MOD_ID__.midnightconfig.<字段名>}</li>
 *   <li>悬浮说明 {@code __MOD_ID__.midnightconfig.<字段名>.tooltip}</li>
 *   <li>分类标签页 {@code __MOD_ID__.midnightconfig.category.<分类名>}</li>
 *   <li>枚举取值 {@code __MOD_ID__.midnightconfig.enum.<枚举类简名>.<常量名>}
 *       —— 第二段是枚举<b>类名</b>而不是字段名</li>
 * </ul>
 */
public class __CONFIG_CLASS__ extends MidnightConfig {

	/** 总开关。关掉后本 mod 不注册任何东西，行为回到原版。 */
	@Entry(category = "general")
	public static volatile boolean enabled = true;

	// 加滑块：
	// @Entry(isSlider = true, min = 0.5D, max = 3.0D, precision = 2, category = "combat")
	// public static volatile double someStrength = 1.0D;
	//
	// 加开关：
	// @Entry(category = "visuals")
	// public static volatile boolean someEffect = true;
	//
	// 加枚举（界面渲染成循环切换按钮，比字符串安全：写错值不可能）：
	// public enum SomeMode { A, B }
	// @Entry(category = "combat")
	// public static volatile SomeMode someMode = SomeMode.A;
}
'@

Write-Tpl "src/main/resources/assets/$ModId/lang/zh_cn.json" @'
{
	"__MOD_ID__.midnightconfig.title": "__DISPLAY_NAME__ 配置",

	"__MOD_ID__.midnightconfig.category.general": "常规",

	"__MOD_ID__.midnightconfig.enabled": "启用本模组",
	"__MOD_ID__.midnightconfig.enabled.tooltip": "总开关。关掉后本模组不注册任何东西，行为回到原版。改完立即生效，不用重启。"
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

## 硬性规则

**本 mod 里所有自定义爆破 / 爆炸 / 冲击，一律走 `impactengine`。**
禁止直接调 `Level#explode(...)`，也禁止手写伤害/击退/破坏方块的逻辑。

原版那一个 `radius` 把破坏范围、伤害、击退强度绑在同一个数上，拆不开；
而引擎的 `blocks.*` / `knockback.*` / `damage.*` 是三组独立字段。
安全底线（基岩等负硬度方块、`wither_immune` / `dragon_immune`、流体、扫描上限）
和全局调参层也只有走引擎才能自动带上。

接入三步、完整字段表、“写代码前先查引擎已经建模了什么”那个教训，
全在 **`../impactengine/AGENTS.md`**。仓库级的五条硬性规则在 **`../AGENTS.md`** 开头。

唯一可以不走的例外是**物理模型不同**（参考 funmod 的门挥扫：绕门轴切线击飞）。
“引擎参数不够用”不算例外，那应该去给引擎加字段。

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

## 游戏内配置界面

本 mod 自带一个 MidnightConfig 配置界面，入口：游戏里「模组列表 → __DISPLAY_NAME__ → 配置」
（需要装 Mod Menu，已在 fabric.mod.json 里声明为 suggests）。

- 配置类：`src/main/java/__ROOT_PACKAGE_PATH__/__CONFIG_CLASS__.java`
- 磁盘位置：`config/__MOD_ID__.json`（首次启动自动生成）
- 翻译：`src/main/resources/assets/__MOD_ID__/lang/zh_cn.json`
- 依赖由 `build.gradle` 里那一行 `apply from: rootProject.file('gradle/config-gui.gradle')` 提供，
  MidnightLib 以 jar-in-jar 形式打进产物，玩家不需要单独装

加一个可调参数只要三步，全程不需要重启游戏：

1. 配置类里加 `@Entry` 字段（`public static volatile`）
2. `zh_cn.json` 里补 `<字段名>` 和 `<字段名>.tooltip` 两个键；新开了 `category` 还要补标签页的键
3. 玩法代码里直接读 `__CONFIG_CLASS__.<字段名>`

完整约定（一个 mod 只能有一个配置类、枚举的翻译键用类名不是字段名、
为什么不能先判断 enabled 再 register）写在配置类自己的 javadoc 里。

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
