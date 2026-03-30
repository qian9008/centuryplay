---
description: 云编译工作流 - 使用 GitHub Actions 或其他 CI/CD 平台构建 Android 应用
---

# 云编译工作流

## 概述

此工作流用于在云端自动构建 Century Play Android 应用，支持多环境构建和自动发布。

## 支持的云平台

### 1. GitHub Actions (推荐)
- 免费额度：每月 2000 分钟
- 支持 Linux、Windows、macOS
- 集成 GitHub 仓库

### 2. GitLab CI/CD
- 自托管或 GitLab.com
- 支持 Docker 容器构建

### 3. Jenkins
- 自托管 CI/CD
- 高度可定制

## GitHub Actions 配置

### 创建工作流文件

在 `.github/workflows/android-build.yml` 中创建：

```yaml
name: Android CI

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]
  release:
    types: [ published ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v4
    
    - name: Set up JDK 21
      uses: actions/setup-java@v4
      with:
        java-version: '21'
        distribution: 'temurin'
        
    - name: Setup Android SDK
      uses: android-actions/setup-android@v3
      with:
        api-level: 35
        build-tools: 35.0.0
        
    - name: Cache Gradle packages
      uses: actions/cache@v3
      with:
        path: |
          ~/.gradle/caches
          ~/.gradle/wrapper
        key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
        restore-keys: |
          ${{ runner.os }}-gradle-
          
    - name: Grant execute permission for gradlew
      run: chmod +x gradlew
      
    - name: Build Debug APK
      run: ./gradlew assembleDebug
      
    - name: Build Release APK
      run: ./gradlew assembleRelease
      env:
        SIGNING_KEY_ALIAS: ${{ secrets.SIGNING_KEY_ALIAS }}
        SIGNING_KEY_PASSWORD: ${{ secrets.SIGNING_KEY_PASSWORD }}
        SIGNING_STORE_PASSWORD: ${{ secrets.SIGNING_STORE_PASSWORD }}
        
    - name: Upload Debug APK
      uses: actions/upload-artifact@v3
      with:
        name: debug-apk
        path: app/build/outputs/apk/debug/app-debug.apk
        
    - name: Upload Release APK
      uses: actions/upload-artifact@v3
      with:
        name: release-apk
        path: app/build/outputs/apk/release/app-release.apk
        
    - name: Release to GitHub
      if: github.event_name == 'release'
      uses: softprops/action-gh-release@v1
      with:
        files: |
          app/build/outputs/apk/release/app-release.apk
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

### 签名配置

在 `app/build.gradle.kts` 中添加签名配置：

```kotlin
android {
    ...
    signingConfigs {
        create("release") {
            keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: project.findProperty("SIGNING_KEY_ALIAS") as String?
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: project.findProperty("SIGNING_KEY_PASSWORD") as String?
            storeFile = file("keystore/release.keystore")?.takeIf { it.exists() } 
                ?: System.getenv("SIGNING_KEY_BASE64")?.let { base64 ->
                    val bytes = Base64.getDecoder().decode(base64)
                    File("keystore/release.keystore").also { 
                        it.parentFile.mkdirs()
                        it.writeBytes(bytes) 
                    }
                }
            storePassword = System.getenv("SIGNING_STORE_PASSWORD") ?: project.findProperty("SIGNING_STORE_PASSWORD") as String?
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

## GitHub Secrets 配置

在仓库设置中添加以下 Secrets：

| Secret 名称 | 描述 |
|-------------|------|
| `SIGNING_KEY_ALIAS` | 签名密钥别名 |
| `SIGNING_KEY_PASSWORD` | 签名密钥密码 |
| `SIGNING_STORE_PASSWORD` | 签名库密码 |
| `SIGNING_KEY_BASE64` | Base64 编码的 keystore 文件 |

### 生成 Base64 Keystore

```bash
# 将 keystore 文件转换为 base64
base64 -i keystore/release.keystore | tr -d '\n'
```

## GitLab CI/CD 配置

在 `.gitlab-ci.yml` 中创建：

```yaml
stages:
  - build
  - test
  - deploy

variables:
  ANDROID_COMPILE_SDK: "35"
  ANDROID_BUILD_TOOLS: "35.0.0"
  ANDROID_SDK_TOOLS: "11076708"

cache:
  paths:
    - .gradle/

before_script:
  - apt-get --quiet update --yes
  - apt-get --quiet install --yes wget tar unzip lib32stdc++6 lib32z1
  - wget --quiet --output-document=/tmp/android-sdk.zip https://dl.google.com/android/repository/commandlinetools-linux-${ANDROID_SDK_TOOLS}_latest.zip
  - unzip /tmp/android-sdk.zip -d /tmp/android-sdk
  - export ANDROID_SDK_ROOT=/tmp/android-sdk/cmdline-tools
  - export PATH=$PATH:$ANDROID_SDK_ROOT/bin
  - echo y | sdkmanager "platforms;android-${ANDROID_COMPILE_SDK}" "build-tools;${ANDROID_BUILD_TOOLS}" "platform-tools"
  - chmod +x gradlew

build_debug:
  stage: build
  script:
    - ./gradlew assembleDebug
  artifacts:
    paths:
      - app/build/outputs/apk/debug/app-debug.apk
    expire_in: 1 week

build_release:
  stage: build
  script:
    - ./gradlew assembleRelease
  artifacts:
    paths:
      - app/build/outputs/apk/release/app-release.apk
    expire_in: 1 month
  only:
    - main
    - tags
```

## 使用方法

### 1. GitHub Actions (推荐)

1. **创建工作流文件**：
   ```bash
   mkdir -p .github/workflows
   # 复制上述 YAML 内容到 .github/workflows/android-build.yml
   ```

2. **配置 Secrets**：
   - 进入仓库 Settings → Secrets and variables → Actions
   - 添加所需的签名相关 Secrets

3. **触发构建**：
   - 推送代码到 main/develop 分支
   - 创建 Pull Request
   - 创建 Release

4. **下载构建产物**：
   - 在 Actions 页面查看构建结果
   - 下载生成的 APK 文件

### 2. 手动触发构建

```bash
# 本地测试构建
./gradlew clean assembleDebug

# 检查构建产物
ls -la app/build/outputs/apk/debug/
```

### 3. 自动发布

当创建 GitHub Release 时，工作流会自动：
- 构建 Release APK
- 上传 APK 到 Release 页面
- 生成下载链接

## 构建优化

### 1. 缓存策略
- Gradle 依赖缓存
- Android SDK 缓存
- Docker 镜像缓存

### 2. 并行构建
```yaml
strategy:
  matrix:
    api-level: [29, 30, 31, 32, 33, 34, 35]
```

### 3. 增量构建
```yaml
- name: Cache build outputs
  uses: actions/cache@v3
  with:
    path: |
      app/build/
      .gradle/buildOutputCleanup/
```

## 故障排除

### 常见问题

1. **构建超时**：
   ```yaml
   timeout-minutes: 30
   ```

2. **内存不足**：
   ```yaml
   env:
     GRADLE_OPTS: -Xmx4g -Dorg.gradle.jvmargs=-Xmx4g
   ```

3. **签名失败**：
   - 检查 Secrets 配置
   - 验证 keystore 格式

### 调试命令

```bash
# 检查 Gradle 版本
./gradlew --version

# 查看项目结构
./gradlew projects

# 检查依赖
./gradlew dependencies

# 清理构建
./gradlew clean
```

## 安全建议

1. **保护签名密钥**：
   - 使用环境变量存储密钥信息
   - 定期轮换签名密钥
   - 限制访问权限

2. **构建安全**：
   - 使用官方 Docker 镜像
   - 定期更新依赖
   - 扫描安全漏洞

3. **发布控制**：
   - 仅允许特定分支发布
   - 使用代码审查
   - 自动化测试验证

## 扩展功能

### 1. 多环境构建
```yaml
strategy:
  matrix:
    variant: [debug, release]
    flavor: [prod, staging]
```

### 2. 自动测试
```yaml
- name: Run Tests
  run: ./gradlew test
- name: Run UI Tests
  run: ./gradlew connectedAndroidTest
```

### 3. 代码质量检查
```yaml
- name: Run Lint
  run: ./gradlew lint
- name: Upload Lint Results
  uses: actions/upload-artifact@v3
  with:
    name: lint-results
    path: app/build/reports/lint-results.html
```

### 4. 通知集成
```yaml
- name: Notify Slack
  uses: 8398a7/action-slack@v3
  with:
    status: ${{ job.status }}
    channel: '#builds'
```

这个云编译工作流提供了完整的自动化构建解决方案，支持多平台部署和安全发布。
