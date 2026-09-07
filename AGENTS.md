# AGENTS.md

Termux Fork —— 基于官方 termux-app 的功能增强 fork。纯 Java（无 Kotlin），source/target 兼容 1.8。Gradle 多模块：`:app`（APK 入口）→ `:terminal-view` → `:terminal-emulator`，公共库 `:termux-shared`。

## 构建与命令
- 需 JDK 17（CI 用 temurin 17）+ Android SDK + NDK（版本集中在根 `gradle.properties`，可用环境变量 `JITPACK_NDK_VERSION` 覆盖）。
- `./gradlew assembleDebug` — 构建 APK。首次构建触发 `downloadBootstraps`：从 GitHub termux-packages releases 下载 bootstrap zip 到 `app/src/main/cpp/`（SHA-256 校验），必须联网；`./gradlew clean` 会删掉这些 zip。离线构建会失败。
- 包变体由 `TERMUX_PACKAGE_VARIANT` 控制（默认 `apt-android-7`，可选 `apt-android-5`），写入 BuildConfig；variant 与 `TermuxBootstrap.PackageVariant` 不匹配时应用启动即崩溃。CI 用双 variant 矩阵构建。
- Debug 构建默认按 ABI 拆分 APK（`TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS`），输出命名为 `termux-app_<tag>_<abi>.apk`。
- `./gradlew test` — 全部单元测试（CI 即此命令）。聚焦运行：
  - `./gradlew :terminal-emulator:test`
  - `./gradlew :terminal-emulator:test --tests "com.termux.terminal.TerminalTest"`
- CI 只有 Build（assembleDebug）和 Unit tests 两个工作流，没有独立的 lint/typecheck 工作流。`Validate Gradle Wrapper` 工作流为既有失败（引用了不存在的 `gradle/actions@5`），与代码改动无关。
- 本地不要编译（开发机 NDK 环境不完整），一律推送后由 CI 构建验证。

## Fork 功能与架构
- **应用名**：`Termux Fork`，定义于 `app/build.gradle` 的 `manifestPlaceholders.TERMUX_APP_NAME` 与 `app/src/main/res/values/strings.xml` 的 `<!ENTITY TERMUX_APP_NAME>`（两处需同步）。`TermuxConstants.TERMUX_APP_NAME` 保持 `Termux`（插件匹配与原版信息展示用）。
- **版本号**：`versionName 0.119.0-beta.3` / `versionCode 1022`，与上游 beta 对齐以便覆盖安装；不要随意 bump。
- **双栏文件管理器**（`com.termux.app.filemanager` + `FileManagerActivity`）：左栏 Termux `$HOME`、右栏 `/storage/emulated/0`；`FileEntry` 统一磁盘文件与压缩包内条目（包内只读）。滑动区间多选（ItemTouchHelper，`onChildDraw` 置零防位移）、长按操作弹窗（双列图标网格）、按后缀默认打开方式（SharedPreferences `file_manager` 的 `open_default_<ext>`）、当前目录递归搜索、`..`/`←` 导航行与每栏历史栈。侧栏不记忆路径，每次从根目录开始。
- **压缩包**：zip/jar/apk 走 **zip4j**（密码支持）；tar/tgz/7z 走 **commons-compress 1.24.0**。加密包密码经 `ArchiveSource.PasswordRequiredException`/`WrongPasswordException` 驱动弹窗询问，按包路径缓存。
- **编辑器**：Sora Editor 0.23.6（Kotlin 库，Java 调用，仅传递 kotlin-stdlib）+ 自写 `RegexHighlightLanguage`（`SimpleAnalyzeManager` + `MappedSpans.Builder`）；暗色用 `SchemeDarcula`。
- **看图**：PhotoView 2.3.0（jitpack，仓库已配置）。
- **侧栏工具弹窗**（`com.termux.app.tools.TermuxToolsDialog`）：termux-api/termux-tools 快捷入口，全部以 `bash -lc "命令 ; exec bash"` 新终端会话执行（`; exec bash` 保活以保留输出）；缺包时首项为 `pkg install -y termux-api termux-tools`。
- **高级设置**（`PropertiesPreferencesFragment`）：直接读写 `~/.termux/termux.properties`（保留注释）。DataStore 必须读**原始文件**而非解析后的 Properties——`use-black-ui` 等键在解析时会被 TermuxSharedProperties 替换，读内存值会导致开关回弹。保存后不自动刷新，提示用户重启。
- **崩溃报告**：`CrashReportLauncher` 启动时检测 `~/crash_log.md` → 弹 `CrashReportActivity`（替代上游通知），支持分享/导出到 Download/上传 LogShare（`POST https://api.logshare.cn/v1/log`，JSON `{content, source}`，返回 `url`）。
- **会话数无上限**（上游 `MAX_SESSIONS = 8` 已移除）。
- **侧栏外观**：不透明背景（模糊方案已整体移除），头部为标题左上 + 设置/文件/扳手图标右下。

## 关键约束
- C 代码（`terminal-emulator/src/main/jni`、`app/src/main/cpp`）以 `-Wall -Wextra -Werror` 编译，任何 C 警告都会导致构建失败。
- SDK 版本集中在根 `gradle.properties`（minSdk 21 / targetSdk 28 / compileSdk 36）。`app/build.gradle` 的 `versionName` 必须严格符合 semver 2.0.0，否则构建直接失败；发布 tag 也要带 patch 号。
- **commons-compress 钉死 1.24.0**：1.26+ 新增 commons-io 硬依赖；1.28 的 `ZipFile` 使用 `List.addLast`（Java 21 API），在本仓库钉定的 `desugar_jdk_libs 1.1.5` 下运行时崩溃（`NoSuchMethodError`）。zip 一律走 zip4j，正是为此。升级前必须用 `javap -c` 核查所用类的字节码。
- zip4j 2.x 无 `WrongPasswordException` 类，密码错误判定用 `ZipException.getType() == Type.WRONG_PASSWORD`；commons-compress 的 7z 加密异常是 `org.apache.commons.compress.PasswordRequiredException`（注意与自写同名类的包名区分），错密码表现为 `org.tukaani.xz.CorruptedInputException`。
- 共享常量/工具必须放 `termux-shared`；fork 专属功能放 `app` 模块新包（`filemanager`/`tools`/`crash`），不污染 shared。核心常量在 `termux-shared/.../TermuxConstants.java`。
- 测试框架：`terminal-emulator` 用纯 JUnit4，`app` 用 Robolectric；`terminal-emulator` 设有 `unitTests.returnDefaultValues = true`。

## 提交信息（仓库强制约定，与通用习惯不同）
Conventional Commits，但 type 必须首字母大写，且只允许：`Added` / `Changed` / `Deprecated` / `Removed` / `Fixed` / `Security`。描述用现在时，冒号后有空格，breaking change 在冒号前加 `!`（如 `Changed!: ...`）。changelog 由该格式自动生成；不要用小写 `feat:` / `fix:` 形式。

## 格式
- 4 空格缩进、LF、UTF-8（`.editorconfig`）；YAML 为 2 空格。
- `.gitattributes` 强制 `*.gradle` / `*.mk` / `*.sh` 为 LF，`*.bat` 为 CRLF。
