# Open Doubao 展示页

纯静态页面，无依赖、无需构建。从仓库根目录启动：

```sh
python3 -m http.server 4175 --bind 127.0.0.1 --directory website
```

打开 <http://127.0.0.1:4175>。发布时将 `website/` 作为静态站点目录；页面使用相对资源路径，也支持放在子目录中。

## 线上部署

访问 [open-doubao.moonrailgun.com](https://open-doubao.moonrailgun.com)，备用地址为 [open-doubao.vercel.app](https://open-doubao.vercel.app)。

Vercel 项目 `open-doubao` 位于个人空间 `lyon-chens-projects`，关联本仓库，Root Directory 设为 `website`，Framework Preset 设为 `Other`，无需安装依赖或构建。Cloudflare 的 `open-doubao` CNAME 指向 `b02b3074dd4d816b.vercel-dns-017.com`，使用仅 DNS 模式。

## 展示素材

素材复用自[项目演示 PPT](https://kaonai.feishu.cn/slides/MkkqsrdL8lj1JLd65Aicv1tGnpc)对应的本地文件。公开展示副本已将姓名替换为 `Demo User`、个人签名替换为“演示账号”，并处理个人头像、账号水印及局域网 IP；演示步骤与时序保持不变。

| 文件 | 来源与用途 |
| --- | --- |
| `assets/logo.png` | 项目现有 `desktop/public/logo.png` |
| `assets/phone-home.jpg` | PPT 手机任务完成截图 |
| `assets/desktop-workspace.jpg` | PPT 桌面工作台截图 |
| `assets/task-preview.gif` | 四应用演示的加速预览，用于根 README |
| `assets/task-demo.mp4` | 脱敏后的四应用录屏，2 倍速 |
| `assets/task-demo-realtime.mp4` | 同一演示的实时录屏，保留原素材已有的暂停剪除 |
| `assets/virtual-demo.mp4` | 虚拟屏演示，等待部分已加速 |
| `assets/virtual-poster.jpg` | 虚拟屏视频第 8 秒的封面帧 |

视频只在点击播放后加载；页面不依赖外部字体，访问统计使用 Tianji。文档与 GitHub 链接指向项目仓库。
