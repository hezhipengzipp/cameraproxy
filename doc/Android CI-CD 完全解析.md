# Android CI/CD 完全解析

一句话总结：CI/CD 是一套自动化软件开发实践，CI（持续集成）负责自动构建和测试，CD（持续交付/部署）负责自动发布到分发渠道，例如 Google Play、测试平台、内测群。目标是让开发者专注写代码，构建、测试、发布流程自动完成。

## 一、CI/CD 解决了什么问题？

### 传统开发流程的痛点

```text
开发写代码
  -> 本地手动编译
  -> 手动修改版本号
  -> 手动选择签名文件
  -> 手动打 Release 包
  -> 手动上传测试平台
  -> 手动通知测试人员
```

常见问题：

- 每次发布都要重复这些手动操作，耗时 1 小时以上。
- 容易出错，例如忘记改版本号、用错签名文件。
- 测试不充分，手动测试覆盖不全。
- 反馈周期长，测试人员等半天才能拿到包。

### 使用 CI/CD 后的流程

```text
开发写代码
  -> git push
  -> CI 自动拉代码
  -> 自动 Lint
  -> 自动测试
  -> 自动编译 APK/AAB
  -> CD 自动上传分发平台
  -> 自动通知测试人员
```

优势：

- 开发者只需 `git push`，后续全自动。
- 10-15 分钟完成全流程。
- 每次提交都自动测试，问题早发现。
- 测试人员自动收到新包，无需催促。

## 二、CI/CD 核心概念

### CI：持续集成（Continuous Integration）

定义：每当代码提交到仓库，自动进行构建和测试，确保新代码不会破坏现有功能。

```text
git push / pull request
  -> 拉取代码
  -> 安装依赖
  -> Lint 检查
  -> 单元测试
  -> 编译 Debug APK
  -> 输出检查结果
```

Android 场景：

- Lint 检查：代码规范、潜在 bug。
- 单元测试：JUnit、Robolectric。
- 编译：生成 Debug APK。
- 静态分析：检测内存泄漏、安全漏洞。

### CD：持续交付/部署（Continuous Delivery/Deployment）

定义：将通过 CI 的产物，自动分发给测试人员或直接发布到应用商店。

```text
CI 通过
  -> 编译 Release APK/AAB
  -> 签名
  -> 上传分发渠道
  -> 生成下载链接
  -> 通知测试/产品/开发
```

持续交付和持续部署的区别：

- 持续交付：自动准备好可发布产物，最终发布通常需要人工确认。
- 持续部署：自动发布到目标环境，不需要人工确认。

## 三、Android CI/CD 典型流程图

```text
开发者提交代码
  |
  v
GitHub/GitLab 触发流水线
  |
  v
拉取代码
  |
  v
设置 JDK / Android SDK / Gradle
  |
  v
依赖缓存恢复
  |
  v
Lint 检查
  |
  v
单元测试
  |
  v
编译 Debug APK
  |
  v
是否发布分支或 Tag?
  |
  +-- 否 --> 上传测试报告和构建产物
  |
  +-- 是 --> 编译 Release APK/AAB
              |
              v
             签名
              |
              v
             上传蒲公英 / Firebase / Google Play
              |
              v
             通知测试人员
```

## 四、常用 Android CI/CD 工具对比

| 工具 | 优点 | 缺点 | 价格 | 适用场景 |
| --- | --- | --- | --- | --- |
| GitHub Actions | 深度集成 GitHub，免费额度足 | 自定义环境有限制 | 免费 2000 分钟/月 | 开源项目、小团队 |
| GitLab CI | 一体化平台，配置文件直观 | 自托管需要维护 | 免费 400 分钟/月 | 公司内部 GitLab 用户 |
| Jenkins | 完全自定义，插件丰富 | 需要自己维护服务器 | 免费，需服务器 | 复杂定制需求的大团队 |
| CircleCI | 速度快，缓存机制好 | 免费额度较少 | 免费 6000 分钟/月 | 中小团队 |
| Bitrise | Android 专用，可视化配置 | 价格较高 | 免费 300 分钟/月 | 移动端专用团队 |
| 腾讯 Coding | 国内速度快 | 生态相对封闭 | 有免费额度 | 国内团队 |
| 阿里云效 | 集成阿里云生态 | 配置较复杂 | 有免费额度 | 阿里云用户 |

快速选择建议：

- 代码托管在 GitHub：优先 GitHub Actions。
- 公司内部 GitLab：优先 GitLab CI。
- 需要高度定制、自建机器或复杂权限：考虑 Jenkins。
- 纯移动端团队且希望少维护：考虑 Bitrise。
- 国内网络和企业协作优先：考虑 Coding 或阿里云效。

## 五、Android CI/CD 核心配置

### 5.1 GitHub Actions 配置示例

```yaml
# .github/workflows/android-ci.yml
name: Android CI/CD

on:
  push:
    branches: [ main, develop ]
    tags: [ 'v*' ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      # 1. 设置 JDK
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      # 2. 缓存 Gradle
      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      # 3. 执行 Lint 和测试
      - name: Run Lint
        run: ./gradlew lint

      - name: Run Tests
        run: ./gradlew test

      # 4. 编译 Debug APK
      - name: Build Debug APK
        run: ./gradlew assembleDebug

      # 5. 上传产物
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk

      # 6. 如果是 Tag，编译 Release 并分发
      - name: Build Release APK
        if: startsWith(github.ref, 'refs/tags/')
        run: ./gradlew assembleRelease

      - name: Upload to Google Play
        if: startsWith(github.ref, 'refs/tags/')
        uses: r0adkll/upload-google-play@v1
        with:
          serviceAccountJson: ${{ secrets.SERVICE_ACCOUNT_JSON }}
          packageName: com.example.app
          releaseFiles: app/build/outputs/apk/release/*.apk
          track: production
```

### 5.2 GitLab CI 配置示例

```yaml
# .gitlab-ci.yml
image: openjdk:17-jdk

variables:
  ANDROID_COMPILE_SDK: "34"
  ANDROID_BUILD_TOOLS: "34.0.0"

before_script:
  - apt-get update && apt-get install -y wget unzip
  - wget https://dl.google.com/android/repository/commandlinetools-linux-latest.zip
  - unzip commandlinetools-linux-latest.zip -d android-sdk
  - export ANDROID_SDK_ROOT=$PWD/android-sdk
  - export PATH=$PATH:$ANDROID_SDK_ROOT/cmdline-tools/bin

cache:
  paths:
    - ~/.gradle/caches/
    - ~/.gradle/wrapper/

stages:
  - build
  - test
  - deploy

lint:
  stage: build
  script:
    - ./gradlew lint
  artifacts:
    paths:
      - app/build/reports/lint-results.html

unit_test:
  stage: test
  script:
    - ./gradlew test

assemble_debug:
  stage: build
  script:
    - ./gradlew assembleDebug
  artifacts:
    paths:
      - app/build/outputs/apk/debug/*.apk

deploy_internal:
  stage: deploy
  only:
    - main
  script:
    - ./gradlew assembleRelease
    - echo "上传到 Firebase App Distribution / 蒲公英 / 通知钉钉或飞书"
```

## 六、CD 分发渠道

| 分发渠道 | 用途 | 配置复杂度 |
| --- | --- | --- |
| Firebase App Distribution | 内测分发，支持测试群组 | ⭐⭐ |
| Google Play Internal Test | 内部测试轨道 | ⭐⭐⭐ |
| Google Play Beta | 公开 Beta 测试 | ⭐⭐⭐ |
| Google Play Production | 正式发布 | ⭐⭐⭐⭐ |
| 蒲公英 / 腾讯 Bugly | 国内快速分发 | ⭐ |
| 钉钉 / 飞书机器人 | 通知测试人员 | ⭐ |
| 邮件通知 | 通知项目成员 | ⭐⭐ |

## 七、国内 CI/CD 特别注意事项

| 问题 | 解决方案 |
| --- | --- |
| Gradle 下载慢 | 使用国内镜像，例如阿里云、腾讯云 |
| Google Play 上传失败 | 使用代理或国内发布渠道 |
| GitHub Actions 网络不稳定 | 使用 Gitee Go、Coding、阿里云效 |
| 需要多个签名环境 | 使用 CI 变量存储 Keystore 密码 |
| UI 测试需要模拟器 | 使用 macOS 构建机，支持硬件加速 |

## 八、总结速记

```text
┌─────────────────────────────────────────────────────────────┐
│                 Android CI/CD 核心要点                       │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  CI（持续集成）：                                            │
│    每次 git push -> 自动编译 + 自动测试                      │
│    价值：早发现、早解决，避免“在我电脑上是好的”               │
│                                                             │
│  CD（持续交付/部署）：                                       │
│    通过 CI 后 -> 自动分发到测试/生产环境                     │
│    价值：一键发布，测试人员自动获取新包                      │
│                                                             │
│  典型流程：                                                  │
│    git push -> 拉取代码 -> Lint -> 单元测试 -> 编译 -> 分发 -> 通知│
│                                                             │
│  常用工具：                                                  │
│    GitHub Actions / GitLab CI / Jenkins / Bitrise           │
│                                                             │
│  分发渠道：                                                  │
│    Firebase / Google Play / 蒲公英 / 钉钉通知                │
│                                                             │
│  口诀：                                                      │
│    CI 保质量，每次提交自动测                                 │
│    CD 提效率，一键分发不用愁                                 │
│    配置写好 git push，喝杯咖啡等结果                         │
└─────────────────────────────────────────────────────────────┘
```

## 九、本项目中的对应关系

本项目当前使用：

- CI/CD 平台：GitHub Actions。
- 自动化发布工具：Fastlane。
- 构建工具：Gradle / Android Gradle Plugin。
- 分发渠道：蒲公英。

对应流程：

```text
push main
  -> GitHub Actions
  -> bundle exec fastlane beta
  -> ./gradlew clean
  -> ./gradlew assembleRelease
  -> 查找 client/server release APK
  -> 上传蒲公英
  -> 上传 GitHub Actions artifact
```

简单理解：

```text
GitHub Actions / CI/CD = 谁来跑、什么时候跑、在哪跑
Fastlane = 跑什么发布步骤
Gradle = 怎么编译 Android APK
蒲公英 = APK 上传到哪里给测试安装
```
