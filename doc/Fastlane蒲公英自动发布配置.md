# Fastlane 蒲公英自动发布配置

本文记录本项目通过 GitHub Actions 触发 Fastlane 构建 Release APK，并在构建成功后自动上传到蒲公英的配置方式。

## 整体流程

当前发布流程由 `.github/workflows/deploy.yml` 触发：

1. 向 `main` 分支 push 代码。
2. GitHub Actions 拉取代码并设置 JDK 17、Ruby、Gradle。
3. 执行 `bundle exec fastlane beta`。
4. Fastlane 执行 `clean` 和 `assembleRelease`。
5. 构建成功后查找以下 APK：
   - `client/build/outputs/apk/release/*.apk`
   - `server/build/outputs/apk/release/*.apk`
6. 调用蒲公英上传接口上传 APK。
7. 同时将 APK 作为 GitHub Actions artifact 保存。

## Fastfile 配置

发布入口是 `fastlane/Fastfile` 里的 `beta` lane：

```ruby
lane :beta do
  gradle(task: "clean", project_dir: ".")

  gradle(
    task: "assembleRelease",
    project_dir: ".",
    flags: "--stacktrace",
    properties: properties
  )

  artifacts.each { |apk| upload_to_pgyer(apk) }
end
```

蒲公英上传使用 Ruby 标准库 `net/http`，不依赖额外 fastlane 插件。上传地址为：

```text
https://api.pgyer.com/apiv2/app/upload
```

上传成功后，GitHub Actions 日志中会出现：

```text
蒲公英上传成功: xxx.apk
https://www.pgyer.com/xxxx
```

如果构建成功但没有找到 APK，Fastlane 会直接失败：

```text
未找到 release APK，请检查 Gradle 输出路径
```

这样可以避免“构建成功但实际没有上传”的假成功。

## GitHub Actions 配置

workflow 文件是 `.github/workflows/deploy.yml`，触发条件是：

```yaml
on:
  push:
    branches: [main]
```

Fastlane 步骤会注入蒲公英相关环境变量：

```yaml
env:
  PGYER_API_KEY: ${{ secrets.PGYER_API_KEY }}
  PGYER_PASSWORD: ${{ secrets.PGYER_PASSWORD }}
  PGYER_INSTALL_TYPE: ${{ vars.PGYER_INSTALL_TYPE }}
  PGYER_UPDATE_DESCRIPTION: ${{ github.event.head_commit.message }}
  PGYER_UPLOAD_REQUIRED: 'true'
```

`PGYER_UPLOAD_REQUIRED=true` 表示 CI 必须上传蒲公英。如果没有配置 `PGYER_API_KEY`，Fastlane 会失败，而不是跳过上传。

## GitHub Secrets 和 Variables

进入 GitHub 仓库：

```text
Settings -> Secrets and variables -> Actions
```

在 `Repository secrets` 添加：

```text
PGYER_API_KEY=蒲公英 API Key
```

可选添加：

```text
PGYER_PASSWORD=安装密码
```

`PGYER_PASSWORD` 不是蒲公英账号的 User Key，而是 App 下载页的安装密码。只有设置为密码安装时才需要。

在 `Repository variables` 可选添加：

```text
PGYER_INSTALL_TYPE=1
```

默认值为 `1`。如果不配置，Fastfile 会自动按 `1` 处理。

## 蒲公英 Key 说明

蒲公英后台通常能看到：

```text
API Key
User Key
```

本项目当前上传接口只使用 `API Key`，对应 GitHub Secret：

```text
PGYER_API_KEY
```

`User Key` 当前不需要配置。

## 如何确认上传成功

打开 GitHub Actions 中对应的 workflow run，查看 `Run Fastlane beta` 步骤日志。

先确认 APK 被找到：

```text
生成了 2 个 APK:
client/build/outputs/apk/release/...
server/build/outputs/apk/release/...
```

再确认蒲公英上传成功：

```text
蒲公英上传成功:
https://www.pgyer.com/...
```

如果只看到：

```text
生成了 0 个 APK:
```

说明 APK 路径匹配失败，需要检查 Gradle 输出路径。

如果看到：

```text
蒲公英上传失败
```

需要根据后面的蒲公英接口返回内容检查 API Key、安装方式、上传限制或网络问题。

## 注意事项

本项目会构建并上传两个 APK：

```text
client
server
```

它们的 Android `applicationId` 不同：

```text
com.example.cameraproxy.client
com.example.cameraproxy.server
```

蒲公英后台可能会显示为两个不同 App，不一定都出现在同一个应用条目下。

## 本地验证

Fastfile 语法检查：

```bash
ruby -c fastlane/Fastfile
```

GitHub Actions YAML 解析检查：

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/deploy.yml"); puts "YAML OK"'
```

本地执行 Fastlane：

```bash
PGYER_API_KEY=你的蒲公英APIKey bundle exec fastlane beta
```

本地执行时如果不想强制上传，可以不设置 `PGYER_UPLOAD_REQUIRED=true`。此时没有 `PGYER_API_KEY` 会跳过上传。

## 提交流程

修改 Fastfile 或 workflow 后提交：

```bash
git add fastlane/Fastfile .github/workflows/deploy.yml doc/Fastlane蒲公英自动发布配置.md
git commit -m "Document Fastlane Pgyer release workflow"
git push origin main
```
